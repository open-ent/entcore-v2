package org.entcore.interoperability.packs;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Set;

/**
 * Ce que le moteur des packs attend d'une application pour en gérer les ressources.
 *
 * <p>Distinct de {@code OeipServiceMapper.importCore} : un import reprend des données AU NOM de
 * celui qui importe ; une installation de pack crée des ressources communes, au nom du compte
 * technique, met à jour celles qui existent déjà (même identifiant global) au lieu de les
 * dupliquer, et ne les rend visibles que par les abonnements.</p>
 *
 * <p>Une ressource de pack, telle que reçue par {@link #create} et {@link #update} :
 * {@code {"globalId", "title", "alignments": [CFItem…], "document": {…format natif…}}}.</p>
 */
public interface PackResourceAdapter {

    /** Identifiant OEIP du service ({@code wiki}, {@code exercizer}…). */
    String serviceId();

    /**
     * Libellé des droits de lecture dans l'annuaire ({@code wiki.read}), ou {@code null} si
     * l'application ne garde pas ses droits par groupe (elle lit alors {@link PackAccess#toJson()}).
     */
    String readRight();

    /** Crée la ressource au nom du compte technique, sans aucun accès. Rend l'identifiant local. */
    Future<String> create(PackOwner owner, JsonObject resource);

    /**
     * Comme {@link #create}, mais dit si l'application a REPRIS une ressource qui existait avant le
     * pack (sujet du catalogue d'exercices de même titre, par exemple) au lieu d'en créer une :
     * {@code {id, adopted}}. Par défaut, rien n'est repris.
     */
    default Future<JsonObject> createOrAdopt(PackOwner owner, JsonObject resource) {
        return create(owner, resource).map(id -> new JsonObject().put("id", id).put("adopted", false));
    }

    /** Remplace le contenu d'une ressource déjà installée ; son accès n'est pas touché. */
    Future<Void> update(PackOwner owner, String localId, JsonObject resource);

    /** Remplace l'accès aux ressources d'après les abonnements en cours. */
    Future<Void> setAccess(Set<String> localIds, PackAccess access);

    /** Demande à l'application de réindexer ces ressources dans l'explorateur, s'il y a lieu. */
    Future<Void> reindex(PackOwner owner, Set<String> localIds);

    /**
     * Libellé du droit de modification ({@code wiki.contrib}) donné aux personnes qui éditent la
     * variante d'un établissement, ou {@code null} si l'application n'a pas de variante par
     * établissement.
     */
    default String editRight() {
        return null;
    }

    /**
     * Variante d'établissement : copie de la ressource, toujours au nom du compte technique, sans
     * aucun accès. Rend l'identifiant local de la copie.
     */
    default Future<String> fork(PackOwner owner, String localId) {
        return Future.failedFuture("[packs] " + serviceId() + " : pas de variante par établissement ; "
                + "chaque enseignant peut copier la ressource dans son propre espace");
    }

    /**
     * Adresse sous laquelle une autre ressource du pack peut citer celle-ci ({@code oeip-ref:}, cf.
     * {@link PackReferences}), ou {@code null} si l'application n'en donne pas.
     */
    default String href(String localId) {
        return null;
    }

    /** Supprime des ressources du compte technique (variantes abandonnées). */
    default Future<Void> delete(PackOwner owner, Set<String> localIds) {
        return Future.succeededFuture();
    }
}
