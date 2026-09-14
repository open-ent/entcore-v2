package org.entcore.interoperability.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.interoperability.services.OeipJob;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Destruction des paquets dont la durée de conservation est écoulée.
 *
 * Chaque paquet annonce dans {@code META/rgpd.json} une durée de conservation et demande au
 * destinataire de le détruire une fois l'import fait. Une plateforme qui l'exige des autres sans
 * l'appliquer à elle-même laisse s'accumuler, sur son propre disque, des exports contenant des
 * personnes identifiées et souvent mineures.
 *
 * <p>La purge travaille sur deux fronts, et le second est le moins évident : supprimer les
 * travaux expirés <b>de la base</b> ne suffit pas. Un travail effacé, un redémarrage au mauvais
 * moment, un plantage en cours d'export laissent un répertoire que plus aucune entrée ne réclame
 * — et qu'une purge branchée sur la seule base ne verra jamais. Ces répertoires sont donc
 * identifiés par différence avec l'inventaire des travaux, et jugés sur leur âge.
 */
public class OeipPurge implements Handler<Long> {

    private static final Logger log = LoggerFactory.getLogger(OeipPurge.class);

    /** Sous-répertoire des exports d'archive intermédiaires, qui portent la charge utile brute. */
    static final String ARCHIVES = "archives";

    private final Vertx vertx;
    private final MongoOeipJobStore jobs;
    private final Path workDir;
    private final long ttlMillis;

    public OeipPurge(Vertx vertx, MongoOeipJobStore jobs, Path workDir, long ttlHours) {
        this.vertx = vertx;
        this.jobs = jobs;
        this.workDir = workDir;
        this.ttlMillis = ttlHours * 3600_000L;
    }

    @Override
    public void handle(Long timerId) {
        final long now = System.currentTimeMillis();
        jobs.expired(now)
            .compose(expired -> supprimerTravaux(expired))
            .compose(supprimes -> jobs.allIds().compose(connus -> balayerOrphelins(connus, now)
                    .map(orphelins -> supprimes + " travail(aux) expiré(s), "
                            + orphelins + " répertoire(s) orphelin(s)")))
            .onSuccess(bilan -> log.info("[OEIP][purge] " + bilan))
            .onFailure(e -> log.error("[OEIP][purge] interrompue : " + e.getMessage(), e));
    }

    // ------------------------------------------------------------------ travaux expirés

    private Future<Integer> supprimerTravaux(JsonArray expired) {
        Future<Integer> chain = Future.succeededFuture(0);
        for (int i = 0; i < expired.size(); i++) {
            final JsonObject job = expired.getJsonObject(i);
            chain = chain.compose(compte -> {
                String jobId = job.getString("_id");
                String etat = job.getString("state");
                if (OeipJob.RUNNING.equals(etat) || OeipJob.QUEUED.equals(etat)) {
                    // Un travail encore « en cours » passé sa durée de conservation n'est pas en
                    // cours : il est resté en plan. Le dire, car c'est le symptôme d'un incident,
                    // et le purger quand même — sinon son répertoire ne partirait jamais.
                    log.warn("[OEIP][purge] travail " + jobId + " expiré alors qu'il était encore "
                            + "à l'état « " + etat + " » : il est resté en plan.");
                }
                supprimerRepertoire(workDir.resolve(jobId));
                return jobs.delete(jobId).map(v -> compte + 1);
            });
        }
        return chain;
    }

    // ------------------------------------------------------------------ répertoires orphelins

    private Future<Integer> balayerOrphelins(Set<String> travauxConnus, long now) {
        return vertx.executeBlocking(promise -> {
            int supprimes = 0;
            for (Path candidat : orphelins(workDir, travauxConnus, now, ttlMillis)) {
                supprimerRepertoire(candidat);
                supprimes++;
            }
            promise.complete(supprimes);
        }, false);
    }

    /**
     * Répertoires à détruire : ceux qu'aucun travail ne réclame et qui ont passé l'âge.
     *
     * Isolé et sans effet de bord pour être éprouvé sans base ni minuterie.
     */
    static List<Path> orphelins(Path workDir, Set<String> travauxConnus, long now, long ttlMillis) {
        List<Path> candidats = new ArrayList<Path>();
        File[] entrees = workDir.toFile().listFiles();
        if (entrees == null) {
            return candidats;
        }
        Set<String> connus = travauxConnus == null
                ? new LinkedHashSet<String>() : travauxConnus;
        for (File entree : entrees) {
            if (!entree.isDirectory()) {
                continue;
            }
            if (ARCHIVES.equals(entree.getName())) {
                // Les exports d'archive intermédiaires ne portent aucun identifiant de travail :
                // ils ne peuvent être jugés que sur leur âge.
                File[] archives = entree.listFiles();
                if (archives != null) {
                    for (File archive : archives) {
                        if (archive.isDirectory() && perime(archive, now, ttlMillis)) {
                            candidats.add(archive.toPath());
                        }
                    }
                }
                continue;
            }
            if (connus.contains(entree.getName())) {
                // Un travail vivant : son sort est décidé par sa date d'expiration, pas ici.
                continue;
            }
            if (perime(entree, now, ttlMillis)) {
                candidats.add(entree.toPath());
            }
        }
        return candidats;
    }

    /**
     * Un répertoire fraîchement créé n'est jamais un orphelin : il appartient très probablement à
     * un export en cours dont le travail n'est pas encore enregistré. Le délai de grâce est la
     * durée de conservation elle-même.
     */
    private static boolean perime(File dir, long now, long ttlMillis) {
        return now - dir.lastModified() > ttlMillis;
    }

    // ------------------------------------------------------------------ suppression

    private static void supprimerRepertoire(Path path) {
        File file = path.toFile();
        if (!file.exists()) {
            return;
        }
        File[] enfants = file.listFiles();
        if (enfants != null) {
            for (File enfant : enfants) {
                supprimerRepertoire(enfant.toPath());
            }
        }
        if (!file.delete()) {
            log.warn("[OEIP][purge] suppression impossible : " + path);
        }
    }
}
