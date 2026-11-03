package org.entcore.interoperability.packs;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installation des packs et abonnements des établissements.
 *
 * <p>Un pack est installé UNE fois par plateforme, au nom du compte technique ; chaque ressource
 * y garde son identifiant global, si bien qu'une nouvelle version met à jour au lieu de dupliquer.
 * Un établissement s'abonne : ses groupes Enseignants (et Élèves si le public les inclut) reçoivent
 * le droit de lecture sur les ressources du pack. Aucune copie : dix établissements abonnés lisent
 * la même ressource.</p>
 *
 * <p>Descripteur d'installation (produit par la lecture d'un paquet OEIP de profil {@code pack}) :
 * <pre>
 * { "pack": { "id", "version", "title", "description", "framework" },
 *   "resources": [ { "globalId", "service", "title", "alignments": [CFItem…], "document": {…} } ] }
 * </pre></p>
 */
public class PackService {

    /** Abonnements d'un pack : explicites, offert à tous, et leur extension aux établissements. */
    private static final class Subscriptions {
        final Map<String, String> explicit;
        final String everyone;
        final Map<String, String> expanded;

        Subscriptions(Map<String, String> explicit, String everyone, Map<String, String> expanded) {
            this.explicit = explicit;
            this.everyone = everyone;
            this.expanded = expanded;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PackService.class);

    private final PackStore store;
    private final PackDirectory directory;
    private final PackOwner owner;
    private final Map<String, PackResourceAdapter> adapters = new LinkedHashMap<String, PackResourceAdapter>();

    public PackService(PackStore store, PackDirectory directory, PackOwner owner) {
        this.store = store;
        this.directory = directory;
        this.owner = owner;
    }

    public PackService register(PackResourceAdapter adapter) {
        adapters.put(adapter.serviceId(), adapter);
        return this;
    }

    public Set<String> services() {
        return Collections.unmodifiableSet(adapters.keySet());
    }

    public PackStore store() {
        return store;
    }

    // ------------------------------------------------------------------ installation

    public Future<JsonObject> install(JsonObject descriptor, String installedBy) {
        final JsonObject pack = descriptor.getJsonObject("pack", new JsonObject());
        final String packId = pack.getString("id");
        if (packId == null || packId.trim().isEmpty()) {
            return Future.failedFuture("[packs] descripteur sans identifiant de pack");
        }
        final JsonObject report = new JsonObject().put("pack", packId).put("version", pack.getString("version"))
                .put("created", new JsonArray()).put("updated", new JsonArray()).put("refused", new JsonArray());
        final long now = System.currentTimeMillis();
        return store.savePack(new JsonObject()
                        .put("_id", packId)
                        .put("version", pack.getString("version"))
                        .put("title", pack.getString("title"))
                        .put("description", pack.getString("description"))
                        .put("framework", pack.getString("framework"))
                        .put("owner", owner.getId())
                        .put("installedBy", installedBy)
                        .put("installedAt", now))
                .compose(v -> store.resources(packId))
                .compose(existing -> {
                    final Map<String, JsonObject> byGlobalId = new LinkedHashMap<String, JsonObject>();
                    for (Object o : existing) {
                        final JsonObject r = (JsonObject) o;
                        byGlobalId.put(r.getString("globalId"), r);
                    }
                    Future<Void> chain = Future.succeededFuture();
                    for (Object o : descriptor.getJsonArray("resources", new JsonArray())) {
                        final JsonObject resource = (JsonObject) o;
                        chain = chain.compose(v -> installOne(packId, pack.getString("version"), resource,
                                byGlobalId.get(resource.getString("globalId")), report));
                    }
                    return chain;
                })
                .compose(v -> offerOnFirstInstall(packId, pack, installedBy))
                .compose(v -> recomputeShares(packId))
                .map(v -> report);
    }

    /**
     * Abonnement offert à tous, quand le pack le déclare ({@code pack.offer.audience}) — c'est ainsi
     * que le catalogue d'exercices reste ouvert à tous les établissements. Créé à la PREMIÈRE
     * installation seulement : un super administrateur qui l'a retiré ne le voit pas revenir avec
     * la version suivante du pack.
     */
    private Future<Void> offerOnFirstInstall(String packId, JsonObject pack, String by) {
        final String audience = pack.getJsonObject("offer", new JsonObject()).getString("audience");
        if (audience == null) {
            return Future.succeededFuture();
        }
        return store.pack(packId).compose(saved -> {
            if (saved != null && saved.getBoolean("offerApplied", false)) {
                return Future.<Void>succeededFuture();
            }
            return store.saveSubscription(new JsonObject()
                            .put("_id", PackStore.key(packId, PackStore.EVERY_STRUCTURE))
                            .put("pack", packId)
                            .put("structure", PackStore.EVERY_STRUCTURE)
                            .put("audience", audience)
                            .put("offered", true)
                            .put("by", by)
                            .put("at", System.currentTimeMillis()))
                    .compose(v -> store.savePack(new JsonObject().put("_id", packId).put("offerApplied", true)));
        });
    }

    private Future<Void> installOne(String packId, String version, JsonObject resource, JsonObject installed,
                                    JsonObject report) {
        final String globalId = resource.getString("globalId");
        final PackResourceAdapter adapter = adapters.get(resource.getString("service"));
        if (globalId == null || adapter == null) {
            report.getJsonArray("refused").add(new JsonObject().put("globalId", globalId)
                    .put("service", resource.getString("service"))
                    .put("reason", adapter == null ? "service sans adaptateur de pack" : "identifiant global absent"));
            return Future.succeededFuture();
        }
        final Future<String> localId = installed != null
                ? adapter.update(owner, installed.getString("localId"), resource).map(v -> installed.getString("localId"))
                : adapter.create(owner, resource);
        return localId.compose(id -> {
            report.getJsonArray(installed != null ? "updated" : "created").add(globalId);
            return store.saveResource(new JsonObject()
                    .put("_id", PackStore.key(packId, globalId))
                    .put("pack", packId)
                    .put("globalId", globalId)
                    .put("service", adapter.serviceId())
                    .put("title", resource.getString("title"))
                    .put("alignments", resource.getJsonArray("alignments", new JsonArray()))
                    .put("localId", id)
                    .put("packVersion", version));
        }).otherwise(error -> {
            // Une ressource refusée ne doit pas faire perdre les autres : le rapport la nomme.
            log.error("[packs] installation de " + globalId + " impossible", error);
            report.getJsonArray("refused").add(new JsonObject().put("globalId", globalId)
                    .put("service", adapter.serviceId()).put("reason", String.valueOf(error.getMessage())));
            return null;
        });
    }

    // ------------------------------------------------------------------ abonnements

    public Future<Void> subscribe(String packId, String structureId, String audience, boolean offered, String by) {
        if (!PackShares.TEACHERS.equals(audience) && !PackShares.TEACHERS_AND_STUDENTS.equals(audience)) {
            return Future.failedFuture("[packs] public inconnu : " + audience);
        }
        return store.pack(packId).compose(pack -> pack == null
                ? Future.<Void>failedFuture("[packs] pack inconnu : " + packId)
                : store.saveSubscription(new JsonObject()
                        .put("_id", PackStore.key(packId, structureId))
                        .put("pack", packId)
                        .put("structure", structureId)
                        .put("audience", audience)
                        .put("offered", offered)
                        .put("by", by)
                        .put("at", System.currentTimeMillis())))
                .compose(v -> recomputeShares(packId));
    }

    public Future<Void> unsubscribe(String packId, String structureId) {
        return store.deleteSubscription(packId, structureId).compose(v -> recomputeShares(packId));
    }

    /**
     * Réécrit les droits de toutes les ressources du pack d'après ses abonnements en cours.
     *
     * <p>Un abonnement explicite d'un établissement l'emporte sur l'abonnement offert à tous : un
     * établissement peut ainsi ouvrir aux élèves ce qui n'est offert qu'aux enseignants.</p>
     */
    public Future<Void> recomputeShares(String packId) {
        return store.subscriptions(packId).compose(subscriptions -> {
            final Map<String, String> audiences = new LinkedHashMap<String, String>();
            String everyone = null;
            for (Object o : subscriptions) {
                final JsonObject s = (JsonObject) o;
                if (PackStore.EVERY_STRUCTURE.equals(s.getString("structure"))) {
                    everyone = s.getString("audience");
                } else {
                    audiences.put(s.getString("structure"), s.getString("audience"));
                }
            }
            final String offeredAudience = everyone;
            final Map<String, String> explicit = new LinkedHashMap<String, String>(audiences);
            final Future<List<String>> schools = offeredAudience == null
                    ? Future.succeededFuture(Collections.<String>emptyList()) : directory.schools();
            return schools.map(ids -> {
                for (String id : ids) {
                    if (!audiences.containsKey(id)) {
                        audiences.put(id, offeredAudience);
                    }
                }
                return new Subscriptions(explicit, offeredAudience, audiences);
            });
        }).compose(subs -> directory.profileGroups(subs.expanded.keySet()).compose(groups ->
                store.resources(packId).compose(resources -> {
                    final Map<String, Set<String>> idsByService = new LinkedHashMap<String, Set<String>>();
                    for (Object o : resources) {
                        final JsonObject r = (JsonObject) o;
                        Set<String> ids = idsByService.get(r.getString("service"));
                        if (ids == null) {
                            ids = new LinkedHashSet<String>();
                            idsByService.put(r.getString("service"), ids);
                        }
                        ids.add(r.getString("localId"));
                    }
                    Future<Void> chain = Future.succeededFuture();
                    for (Map.Entry<String, Set<String>> entry : idsByService.entrySet()) {
                        final PackResourceAdapter adapter = adapters.get(entry.getKey());
                        if (adapter == null) {
                            continue;
                        }
                        final Future<List<String>> actions = adapter.readRight() == null
                                ? Future.succeededFuture(Collections.<String>emptyList())
                                : directory.actions(adapter.readRight());
                        chain = chain.compose(v -> actions
                                .compose(names -> adapter.setAccess(entry.getValue(), new PackAccess(
                                        names.isEmpty() ? new JsonArray()
                                                : PackShares.compute(subs.expanded, groups, new ArrayList<String>(names), null),
                                        subs.explicit, subs.everyone)))
                                .compose(w -> adapter.reindex(owner, entry.getValue())));
                    }
                    return chain;
                })));
    }
}
