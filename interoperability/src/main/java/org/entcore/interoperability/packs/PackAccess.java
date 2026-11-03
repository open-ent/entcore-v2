package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.Map;

/**
 * Qui doit voir les ressources d'un pack, sous les deux formes dont les applications ont besoin.
 *
 * <ul>
 *   <li>{@link #shared()} — tableau {@code shared} déjà calculé (groupes de profil × actions de
 *       lecture), pour les applications qui gardent leurs droits par groupe ;</li>
 *   <li>{@link #toJson()} — abonnements bruts (établissement → public, et public offert à tous),
 *       pour celles qui filtrent par établissement, comme le catalogue d'exercices : y recopier
 *       l'abonnement offert à 366 établissements n'aurait aucun sens.</li>
 * </ul>
 */
public final class PackAccess {

    private final JsonArray shared;
    private final Map<String, String> structures;
    private final String everyone;

    public PackAccess(JsonArray shared, Map<String, String> structures, String everyone) {
        this.shared = shared == null ? new JsonArray() : shared;
        this.structures = structures == null ? Collections.<String, String>emptyMap() : structures;
        this.everyone = everyone;
    }

    public JsonArray shared() {
        return shared;
    }

    /** {@code {"everyone": "teachers" | null, "structures": {"<id>": "teachers+students"}}}. */
    public JsonObject toJson() {
        final JsonObject byStructure = new JsonObject();
        for (Map.Entry<String, String> e : structures.entrySet()) {
            byStructure.put(e.getKey(), e.getValue());
        }
        return new JsonObject().put("everyone", everyone).put("structures", byStructure);
    }
}
