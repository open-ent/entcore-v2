package org.entcore.interoperability.packs;

import io.vertx.core.json.JsonObject;
import org.entcore.common.user.UserInfos;

/**
 * Propriétaire des ressources de pack : un usager TECHNIQUE, sans compte dans l'annuaire.
 *
 * <p>Les contenus d'un pack sont communs à tous les établissements abonnés : ils ne doivent porter
 * le nom d'aucune personne, et surtout pas celui du super administrateur qui les installe. Le même
 * identifiant que le catalogue d'exercices ({@code open-ent-catalogue}, « Open ENT ») est repris,
 * pour qu'une ressource commune se reconnaisse partout au même auteur.</p>
 *
 * <p>Comme il n'existe pas dans l'annuaire, ce compte ne voit aucun groupe : il ne peut pas passer
 * par le service de partage standard, qui vérifie la visibilité. Les droits de ses ressources sont
 * donc écrits par le moteur des packs (cf. {@link PackShares}).</p>
 */
public final class PackOwner {

    public static final String DEFAULT_ID = "open-ent-catalogue";
    public static final String DEFAULT_NAME = "Open ENT";

    private final String id;
    private final String name;

    public PackOwner(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static PackOwner fromConfig(JsonObject packsConfig) {
        final JsonObject config = packsConfig == null ? new JsonObject() : packsConfig;
        return new PackOwner(config.getString("owner-id", DEFAULT_ID), config.getString("owner-name", DEFAULT_NAME));
    }

    public String getId() { return id; }

    public String getName() { return name; }

    /** Identité transmise aux plugins d'explorateur des applications (en-têtes userId/userName). */
    public UserInfos asUser() {
        final UserInfos user = new UserInfos();
        user.setUserId(id);
        user.setUsername(name);
        user.setLogin(id);
        return user;
    }
}
