/*
 * Authentification des clients de l'API /scim/v2 par le serveur OAuth2 du module auth.
 *
 * Le client (l'IAM) est une application déclarée dans le registre d'applications, en grant
 * client_credentials, avec le scope « scim:read » et/ou « scim:write ». Il obtient un jeton par
 * POST /auth/oauth2/token et l'envoie en Bearer. Le jeton est validé par le bus wse.oauth (le même
 * contrôle que UserAuthFilter), puis :
 *  - il doit avoir été délivré à une application (client_credentials : remote_user OAuthSystemUser),
 *    un jeton obtenu au nom d'un utilisateur est refusé ;
 *  - le client doit figurer dans "scim-v2.clients" (liste blanche, obligatoire) ;
 *  - le scope doit contenir celui de l'opération.
 */
package org.entcore.directory.scim;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public final class ScimClientAuth {

    public static final String SYSTEM_USER = "OAuthSystemUser";
    private static final String OAUTH_ADDRESS = "wse.oauth";

    private ScimClientAuth() {}

    /** Valide le jeton auprès du module auth : {client_id, remote_user, scope}, ou échec "scim.token.invalid". */
    public static Future<JsonObject> validate(EventBus eb, String authorization) {
        final Promise<JsonObject> promise = Promise.promise();
        final JsonObject request = new JsonObject()
                .put("headers", new JsonObject().put("Authorization", authorization))
                .put("params", new JsonObject());
        eb.request(OAUTH_ADDRESS, request, ar -> {
            final JsonObject body = ar.succeeded() ? (JsonObject) ar.result().body() : null;
            if (body != null && "ok".equals(body.getString("status"))) promise.complete(body);
            else promise.fail("scim.token.invalid");
        });
        return promise.future();
    }

    /** @return null si le client est autorisé pour ce scope, sinon la clé d'erreur. */
    public static String check(JsonObject tokenInfo, String requiredScope, JsonArray allowedClients) {
        final String clientId = tokenInfo.getString("client_id");
        if (clientId == null || clientId.isEmpty()) return "scim.token.no.client";
        if (!SYSTEM_USER.equals(tokenInfo.getString("remote_user"))) return "scim.token.not.client.credentials";
        if (allowedClients == null || !allowedClients.contains(clientId)) return "scim.client.not.allowed";
        if (!hasScope(tokenInfo.getString("scope"), requiredScope)) return "scim.token.scope";
        return null;
    }

    static boolean hasScope(String scopes, String scope) {
        if (scopes == null) return false;
        for (String s : scopes.split(" ")) {
            if (s.equals(scope)) return true;
        }
        return false;
    }
}
