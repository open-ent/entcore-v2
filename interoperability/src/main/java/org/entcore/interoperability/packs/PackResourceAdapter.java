package org.entcore.interoperability.packs;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Set;

/**
 * Ce que le moteur des packs attend d'une application pour en gérer les ressources.
 *
 * <p>Distinct de {@code OeipServiceMapper.importCore} : un import reprend des données AU NOM de
 * celui qui importe ; une installation de pack crée des ressources communes, au nom du compte
 * technique, met à jour celles qui existent déjà (même identifiant global) au lieu de les
 * dupliquer, et ne les rend visibles que par les abonnements.</p>
 */
public interface PackResourceAdapter {

    /** Identifiant OEIP du service ({@code wiki}, {@code mindmap}…). */
    String serviceId();

    /** Libellé des droits de lecture dans l'annuaire ({@code wiki.read}). */
    String readRight();

    /**
     * Crée la ressource au nom du compte technique, sans aucun partage.
     *
     * @param document document dans le format natif de l'application ({@code extensions.<service>}
     *                 du paquet OEIP)
     * @return l'identifiant local créé
     */
    Future<String> create(PackOwner owner, JsonObject document);

    /** Remplace le contenu d'une ressource déjà installée ; son partage n'est pas touché. */
    Future<Void> update(PackOwner owner, String localId, JsonObject document);

    /** Remplace le tableau {@code shared} des ressources (calculé par {@link PackShares}). */
    Future<Void> setShared(Set<String> localIds, JsonArray shared);

    /** Demande à l'application de réindexer ces ressources dans l'explorateur. */
    Future<Void> reindex(PackOwner owner, Set<String> localIds);
}
