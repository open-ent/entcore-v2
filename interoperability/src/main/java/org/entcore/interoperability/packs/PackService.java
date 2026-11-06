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
 *   "icon": { "mediaType", "sha256", "data" },                                    (facultatif)
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
                .put("created", new JsonArray()).put("adopted", new JsonArray()).put("updated", new JsonArray())
                .put("refused", new JsonArray());
        final long now = System.currentTimeMillis();
        // Une version sans icône retire celle de la version précédente : le pack dit ce qu'il est.
        final JsonObject icon = descriptor.getJsonObject("icon");
        return (icon == null ? store.deleteIcon(packId) : store.saveIcon(packId, icon))
                .compose(saved -> store.savePack(new JsonObject()
                        .put("_id", packId)
                        .put("version", pack.getString("version"))
                        .put("title", pack.getString("title"))
                        .put("description", pack.getString("description"))
                        .put("framework", pack.getString("framework"))
                        .put("owner", owner.getId())
                        .put("installedBy", installedBy)
                        .put("installedAt", now)
                        .put("icon", icon == null ? null : new JsonObject()
                                .put("mediaType", icon.getString("mediaType"))
                                .put("sha256", icon.getString("sha256")))))
                .compose(v -> store.resources(packId))
                .compose(existing -> {
                    final Map<String, JsonObject> byGlobalId = new LinkedHashMap<String, JsonObject>();
                    // Où trouver chaque ressource citée (oeip-ref:) : service et identifiant local,
                    // complétés au fil de l'installation.
                    final Map<String, String> services = new LinkedHashMap<String, String>();
                    final Map<String, String> localIds = new LinkedHashMap<String, String>();
                    for (Object o : existing) {
                        final JsonObject r = (JsonObject) o;
                        byGlobalId.put(r.getString("globalId"), r);
                        services.put(r.getString("globalId"), r.getString("service"));
                        localIds.put(r.getString("globalId"), r.getString("localId"));
                    }
                    final JsonArray incoming = descriptor.getJsonArray("resources", new JsonArray());
                    for (Object o : incoming) {
                        final JsonObject r = (JsonObject) o;
                        services.put(r.getString("globalId"), r.getString("service"));
                    }
                    final Set<String> components = PackReferences.components(incoming);
                    Future<Void> chain = Future.succeededFuture();
                    for (final JsonObject resource : PackReferences.installOrder(incoming)) {
                        chain = chain.compose(v -> installOne(packId, pack.getString("version"), resource,
                                byGlobalId.get(resource.getString("globalId")),
                                components.contains(resource.getString("globalId")), services, localIds, report));
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

    private Future<Void> installOne(String packId, String version, JsonObject cited, JsonObject installed,
                                    boolean component, Map<String, String> services, Map<String, String> localIds,
                                    JsonObject report) {
        final String globalId = cited.getString("globalId");
        final PackResourceAdapter adapter = adapters.get(cited.getString("service"));
        if (globalId == null || adapter == null) {
            report.getJsonArray("refused").add(new JsonObject().put("globalId", globalId)
                    .put("service", cited.getString("service"))
                    .put("reason", adapter == null ? "service sans adaptateur de pack" : "identifiant global absent"));
            return Future.succeededFuture();
        }
        final JsonObject resource;
        try {
            resource = PackReferences.resolve(cited, hrefs(PackReferences.targets(cited), services, localIds));
        } catch (IllegalArgumentException e) {
            report.getJsonArray("refused").add(new JsonObject().put("globalId", globalId)
                    .put("service", adapter.serviceId()).put("reason", e.getMessage()));
            return Future.succeededFuture();
        }
        // Une ressource déjà là (réinstallation, ou reprise par l'application d'un contenu d'avant
        // le pack) n'est pas « créée » : le rapport le distingue.
        final Future<JsonObject> outcome = installed != null
                ? adapter.update(owner, installed.getString("localId"), resource)
                        .map(v -> new JsonObject().put("id", installed.getString("localId")).put("bucket", "updated"))
                : adapter.createOrAdopt(owner, resource).map(r -> new JsonObject().put("id", r.getString("id"))
                        .put("bucket", r.getBoolean("adopted", false) ? "adopted" : "created"));
        return outcome.compose(result -> {
            final String id = result.getString("id");
            report.getJsonArray(result.getString("bucket")).add(globalId);
            localIds.put(globalId, id);
            return store.saveResource(new JsonObject()
                    .put("_id", PackStore.key(packId, globalId))
                    .put("pack", packId)
                    .put("globalId", globalId)
                    .put("service", adapter.serviceId())
                    .put("title", resource.getString("title"))
                    .put("alignments", resource.getJsonArray("alignments", new JsonArray()))
                    .put("localId", id)
                    .put("component", component)
                    .put("packVersion", version));
        }).otherwise(error -> {
            // Une ressource refusée ne doit pas faire perdre les autres : le rapport la nomme.
            log.error("[packs] installation de " + globalId + " impossible", error);
            report.getJsonArray("refused").add(new JsonObject().put("globalId", globalId)
                    .put("service", adapter.serviceId()).put("reason", String.valueOf(error.getMessage())));
            return null;
        });
    }

    /** Adresse de chaque ressource citée qui est déjà installée ; les autres restent absentes. */
    private Map<String, String> hrefs(Set<String> targets, Map<String, String> services,
                                      Map<String, String> localIds) {
        final Map<String, String> hrefs = new LinkedHashMap<String, String>();
        for (String target : targets) {
            final PackResourceAdapter adapter = adapters.get(services.get(target));
            final String localId = localIds.get(target);
            final String href = adapter == null || localId == null ? null : adapter.href(localId);
            if (href != null) {
                hrefs.put(target, href);
            }
        }
        return hrefs;
    }

    // ------------------------------------------------------------------ origine des ressources

    /**
     * Pack d'origine de ressources d'une application, pour qu'elle le montre (titre, icône) à côté
     * d'elles. Une variante d'établissement vient du même pack que l'original. Les identifiants qui
     * ne viennent d'aucun pack sont absents du résultat :
     * <pre>{ "&lt;localId&gt;": { "pack", "title", "icon": sha256 | null, "variant": bool } }</pre>
     */
    public Future<JsonObject> origins(String service, Set<String> localIds) {
        if (localIds.isEmpty()) {
            return Future.succeededFuture(new JsonObject());
        }
        return store.resourcesByLocalId(service, localIds).compose(resources ->
                store.variantsByLocalId(service, localIds).compose(variants -> store.packs().map(all -> {
                    final Map<String, JsonObject> packsById = new LinkedHashMap<String, JsonObject>();
                    for (Object o : all) {
                        packsById.put(((JsonObject) o).getString("_id"), (JsonObject) o);
                    }
                    final JsonObject result = new JsonObject();
                    origins(result, resources, packsById, false);
                    origins(result, variants, packsById, true);
                    return result;
                })));
    }

    private static void origins(JsonObject result, JsonArray entries, Map<String, JsonObject> packsById,
                                boolean variant) {
        for (Object o : entries) {
            final JsonObject entry = (JsonObject) o;
            final JsonObject pack = packsById.get(entry.getString("pack"));
            if (pack == null) {
                continue;
            }
            final JsonObject icon = pack.getJsonObject("icon");
            result.put(entry.getString("localId"), new JsonObject()
                    .put("pack", pack.getString("_id"))
                    .put("title", pack.getString("title"))
                    .put("icon", icon == null ? null : icon.getString("sha256"))
                    .put("variant", variant));
        }
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
                store.resources(packId).compose(resources -> store.variants(packId).compose(variants ->
                        applyAccess(resources, variants, subs, groups)))));
    }

    /**
     * Écrit l'accès de chaque ressource du pack et de chaque variante d'établissement.
     *
     * <ul>
     *   <li>original sans variante : partage commun à tous les abonnés (par lots, par service) ;</li>
     *   <li>original qui a des variantes : le même, sauf les établissements qui ont la leur ;</li>
     *   <li>variante : son seul établissement, selon le public de son abonnement, plus ses éditeurs
     *       (lecture et modification). Un établissement désabonné ne garde rien, pas même sa variante.</li>
     * </ul>
     */
    private Future<Void> applyAccess(JsonArray resources, JsonArray variants, Subscriptions subs,
                                     Map<String, Map<String, String>> groups) {
        final Map<String, Set<String>> variantStructures = new LinkedHashMap<String, Set<String>>();
        for (Object o : variants) {
            final JsonObject v = (JsonObject) o;
            Set<String> structures = variantStructures.get(v.getString("globalId"));
            if (structures == null) {
                structures = new LinkedHashSet<String>();
                variantStructures.put(v.getString("globalId"), structures);
            }
            structures.add(v.getString("structure"));
        }
        Future<Void> chain = Future.succeededFuture();
        for (final String service : adapters.keySet()) {
            final PackResourceAdapter adapter = adapters.get(service);
            final Set<String> shared = new LinkedHashSet<String>();
            final Set<String> components = new LinkedHashSet<String>();
            final List<JsonObject> withVariants = new ArrayList<JsonObject>();
            for (Object o : resources) {
                final JsonObject r = (JsonObject) o;
                if (!service.equals(r.getString("service"))) {
                    continue;
                }
                if (variantStructures.containsKey(r.getString("globalId"))) {
                    withVariants.add(r);
                } else if (r.getBoolean("component", false)) {
                    components.add(r.getString("localId"));
                } else {
                    shared.add(r.getString("localId"));
                }
            }
            final List<JsonObject> forks = new ArrayList<JsonObject>();
            for (Object o : variants) {
                if (service.equals(((JsonObject) o).getString("service"))) {
                    forks.add((JsonObject) o);
                }
            }
            if (shared.isEmpty() && components.isEmpty() && withVariants.isEmpty() && forks.isEmpty()) {
                continue;
            }
            chain = chain.compose(v -> rights(adapter.readRight()).compose(read -> rights(adapter.editRight())
                    .compose(edit -> {
                        final Set<String> touched = new LinkedHashSet<String>(shared);
                        touched.addAll(components);
                        Future<Void> step = adapter.setAccess(shared, new PackAccess(
                                share(subs.expanded, groups, read, null), subs.explicit, subs.everyone));
                        // Un composant (l'enregistrement d'une dictée) s'atteint par la ressource
                        // qui le cite, et celle-ci finit chez les élèves : il leur est ouvert dans
                        // tout établissement abonné, quel que soit le public de l'abonnement.
                        step = step.compose(w -> adapter.setAccess(components, new PackAccess(
                                share(PackShares.withStudents(subs.expanded), groups, read, null),
                                subs.explicit, subs.everyone)));
                        for (final JsonObject r : withVariants) {
                            final Set<String> excluded = variantStructures.get(r.getString("globalId"));
                            touched.add(r.getString("localId"));
                            step = step.compose(w -> adapter.setAccess(Collections.singleton(r.getString("localId")),
                                    new PackAccess(share(subs.expanded, groups, read, excluded),
                                            subs.explicit, subs.everyone)));
                        }
                        for (final JsonObject fork : forks) {
                            final String structure = fork.getString("structure");
                            final String audience = subs.expanded.get(structure);
                            final Map<String, String> only = audience == null
                                    ? Collections.<String, String>emptyMap()
                                    : Collections.singletonMap(structure, audience);
                            final JsonArray forkShared = share(only, groups, read, null);
                            if (audience != null) {
                                final List<String> editActions = new ArrayList<String>(read);
                                editActions.addAll(edit);
                                PackShares.withUsers(forkShared,
                                        fork.getJsonArray("editors", new JsonArray()).getList(), editActions);
                            }
                            touched.add(fork.getString("localId"));
                            step = step.compose(w -> adapter.setAccess(Collections.singleton(fork.getString("localId")),
                                    new PackAccess(forkShared, only, null)));
                        }
                        return step.compose(w -> adapter.reindex(owner, touched));
                    })));
        }
        return chain;
    }

    private Future<List<String>> rights(String right) {
        return right == null ? Future.succeededFuture(Collections.<String>emptyList()) : directory.actions(right);
    }

    private static JsonArray share(Map<String, String> audiences, Map<String, Map<String, String>> groups,
                                   List<String> actions, Set<String> excluded) {
        return actions.isEmpty() ? new JsonArray()
                : PackShares.compute(audiences, groups, new ArrayList<String>(actions), excluded);
    }

    // ------------------------------------------------------------------ variantes d'établissement

    /**
     * Variante d'un établissement : copie de la ressource, au nom du compte technique, partagée avec
     * ce seul établissement et modifiable par {@code editor} (l'ADML qui la demande). L'original
     * reste commun aux autres abonnés et n'est plus montré à cet établissement.
     */
    public Future<JsonObject> createVariant(String packId, String structureId, String globalId, String editor) {
        final String key = PackStore.key(packId, globalId);
        return store.resource(key).compose(resource -> {
            if (resource == null) {
                return Future.failedFuture("[packs] ressource inconnue dans ce pack : " + globalId);
            }
            final PackResourceAdapter adapter = adapters.get(resource.getString("service"));
            if (adapter == null) {
                return Future.failedFuture("[packs] service sans adaptateur : " + resource.getString("service"));
            }
            return store.variant(PackStore.key(key, structureId)).compose(existing -> existing != null
                    ? Future.succeededFuture(existing)
                    : store.pack(packId).compose(pack -> adapter.fork(owner, resource.getString("localId"))
                            .compose(localId -> {
                                final JsonObject variant = new JsonObject()
                                        .put("_id", PackStore.key(key, structureId))
                                        .put("pack", packId)
                                        .put("globalId", globalId)
                                        .put("service", adapter.serviceId())
                                        .put("structure", structureId)
                                        .put("localId", localId)
                                        .put("editors", new JsonArray().add(editor))
                                        .put("basedOnVersion", pack == null ? null : pack.getString("version"))
                                        .put("by", editor)
                                        .put("at", System.currentTimeMillis());
                                return store.saveVariant(variant).map(v -> variant);
                            })));
        }).compose(variant -> recomputeShares(packId).map(v -> variant));
    }

    /** Abandonne la variante : l'établissement retrouve l'original commun, la copie est supprimée. */
    public Future<Void> deleteVariant(String packId, String structureId, String globalId) {
        final String id = PackStore.key(PackStore.key(packId, globalId), structureId);
        return store.variant(id).compose(variant -> {
            if (variant == null) {
                return Future.<Void>succeededFuture();
            }
            final PackResourceAdapter adapter = adapters.get(variant.getString("service"));
            return store.deleteVariant(id)
                    .compose(v -> recomputeShares(packId))
                    .compose(v -> adapter == null ? Future.<Void>succeededFuture()
                            : adapter.delete(owner, Collections.singleton(variant.getString("localId"))));
        });
    }
}
