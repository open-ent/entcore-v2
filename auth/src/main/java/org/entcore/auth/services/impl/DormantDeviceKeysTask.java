/* Copyright © "Open Digital Education", 2025
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.
 *
 */

package org.entcore.auth.services.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.auth.services.DeviceCredentialService;
import org.entcore.common.neo4j.Neo4j;

import java.util.HashSet;
import java.util.Set;

/**
 * Retire les clés d'appareil dont on n'a plus de nouvelles depuis longtemps.
 *
 * <h3>Pourquoi cette tâche existe</h3>
 * <p>Sur Android, changer d'empreinte enrôlée détruit la clé : c'est
 * {@code invalidatedByBiometricEnrollment}, et l'OS ne prévient personne. L'application le
 * constate à la première signature et peut alors avertir le serveur — mais seulement si elle est
 * rouverte. Si le téléphone est perdu, réinitialisé, ou simplement jamais rallumé, la clé reste
 * active en base.</p>
 *
 * <p>Une clé active qui ne peut plus rien signer n'est pas inoffensive : elle compte comme
 * facteur de possession. Le compte est alors soumis à un second facteur qu'il n'a plus aucun
 * moyen d'apporter — autrement dit, enfermé dehors sans que rien ne l'ait signalé. Cette tâche
 * est le filet qui rattrape ces clés fantômes.</p>
 *
 * <p>Le délai est volontairement long : une clé peut dormir des mois sans rien avoir de
 * suspect, par exemple celle d'une tablette qu'on ne ressort qu'à la rentrée. On ne cherche pas
 * à faire le ménage, on cherche à ne laisser personne dehors.</p>
 */
public class DormantDeviceKeysTask implements Handler<Long> {

    private static final Logger log = LoggerFactory.getLogger(DormantDeviceKeysTask.class);

    /** Au-delà, une clé prouvée est tenue pour perdue. */
    private static final long DEFAULT_IDLE_DAYS = 180L;

    private static final long DAY_MS = 24 * 3600 * 1000L;

    private final DeviceCredentialService credentialService;
    private final long idleDays;

    public DormantDeviceKeysTask(final DeviceCredentialService credentialService, final Long idleDays) {
        this.credentialService = credentialService;
        this.idleDays = idleDays != null && idleDays > 0 ? idleDays : DEFAULT_IDLE_DAYS;
    }

    @Override
    public void handle(final Long event) {
        run().onFailure(e -> log.error("Dormant device keys task failed", e));
    }

    /** Exposée pour pouvoir être déclenchée à la demande et observée en test. */
    public Future<Integer> run() {
        final long idleSince = System.currentTimeMillis() - idleDays * DAY_MS;
        return credentialService.revokeDormant(idleSince, "system:dormant")
                .compose(revoked -> {
                    if (revoked == null || revoked.isEmpty()) {
                        return Future.succeededFuture(0);
                    }
                    log.info("Revoked " + revoked.size() + " dormant device key(s)");
                    // La projection de chaque compte touché doit suivre, sinon l'annuaire
                    // continuerait d'affirmer qu'ils disposent d'un facteur de possession.
                    return refreshProjections(usersOf(revoked)).map(unused -> revoked.size());
                });
    }

    private static Set<String> usersOf(final JsonArray credentials) {
        final Set<String> userIds = new HashSet<>();
        for (Object o : credentials) {
            if (o instanceof JsonObject) {
                final String userId = ((JsonObject) o).getString("userId");
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }
        return userIds;
    }

    /**
     * Recalcule le drapeau de chaque compte concerné, et efface le choix « appareil » devenu
     * impossible. Une seule requête : la purge peut toucher beaucoup de comptes d'un coup, et
     * les traiter un par un ferait autant d'allers-retours.
     */
    private Future<Void> refreshProjections(final Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Future.succeededFuture();
        }
        final Promise<Void> promise = Promise.promise();
        final JsonArray ids = new JsonArray();
        userIds.forEach(ids::add);
        Neo4j.getInstance().execute(
                "MATCH (u:User) WHERE u.id IN {ids} SET u.deviceMfa = false, "
                        // Seul le choix devenu impossible est effacé : un compte qui avait
                        // choisi le courriel ou l'application garde le sien.
                        + "u.mfaType = CASE WHEN u.mfaType = 'device' THEN null ELSE u.mfaType END",
                new JsonObject().put("ids", ids),
                res -> {
                    if (!"ok".equals(res.body().getString("status"))) {
                        log.error("Unable to refresh device MFA projections after purge : "
                                + res.body().getString("message"));
                    }
                    promise.complete();
                });
        return promise.future();
    }

}
