package org.entcore.interoperability.spi;

import io.vertx.core.json.JsonArray;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registre des services réellement gréés pour OEIP sur cette plateforme.
 *
 * Règle cardinale : ce registre — et lui seul — répond à la question « ce service est-il
 * exportable ici ». Un service absent du registre produit une erreur explicite, jamais un
 * dossier vide dans le paquet. C'est l'anti-patron à ne pas reproduire du format d'archive,
 * où un module sans implémentation se contente de ne pas répondre.
 */
public class OeipProviderRegistry {

    private final Map<String, OeipServiceMapper> mappers = new LinkedHashMap<>();

    public OeipProviderRegistry register(OeipServiceMapper mapper) {
        if (mapper == null || mapper.serviceId() == null) {
            throw new IllegalArgumentException("mapper ou serviceId nul");
        }
        mappers.put(mapper.serviceId(), mapper);
        return this;
    }

    public boolean isRegistered(String serviceId) {
        return mappers.containsKey(serviceId);
    }

    public OeipServiceMapper get(String serviceId) {
        return mappers.get(serviceId);
    }

    public Collection<OeipServiceMapper> all() {
        return mappers.values();
    }

    public int size() {
        return mappers.size();
    }

    /**
     * @return la capacité du service, ou {@code null} s'il n'est pas gréé ici.
     *         L'appelant DOIT traiter le null comme un refus explicite.
     */
    public OeipCapability capability(String serviceId) {
        OeipServiceMapper m = mappers.get(serviceId);
        return m == null ? null : m.capability();
    }

    public JsonArray capabilitiesJson() {
        JsonArray array = new JsonArray();
        for (OeipServiceMapper m : mappers.values()) {
            array.add(m.capability().toJson());
        }
        return array;
    }
}
