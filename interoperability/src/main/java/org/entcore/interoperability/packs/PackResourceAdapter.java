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

    /** Remplace le contenu d'une ressource déjà installée ; son accès n'est pas touché. */
    Future<Void> update(PackOwner owner, String localId, JsonObject resource);

    /** Remplace l'accès aux ressources d'après les abonnements en cours. */
    Future<Void> setAccess(Set<String> localIds, PackAccess access);

    /** Demande à l'application de réindexer ces ressources dans l'explorateur, s'il y a lieu. */
    Future<Void> reindex(PackOwner owner, Set<String> localIds);
}
