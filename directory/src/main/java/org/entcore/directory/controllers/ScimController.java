/*
 * Endpoint d'ingestion des Security Event Tokens (SCIM/SET) émis par l'IAM (Magellan).
 *   POST /scim/events   (Content-Type: application/secevent+jwt, corps = JWT signé)
 * Vérifie la signature (JWKS) puis relaie l'événement décodé au worker feeder (bus entcore.feeder,
 * action "scim-event"). Répond 202 si accepté, 401 si signature invalide.
 *
 * ⚠️ Sécurité de la route : cet endpoint est machine-à-machine (pas d'utilisateur connecté). Il doit être
 * exposé en accès non authentifié MAIS protégé par la vérification de signature + un contrôle réseau
 * (liste d'IP / mTLS) — le binding "route publique" côté entcore reste à valider sur une instance lancée
 * (cf. cadrage-phase3.md). Non testé en local (le chemin HTTP nécessite un entcore démarré).
 */
package org.entcore.directory.controllers;

import fr.wseduc.rs.Post;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import fr.wseduc.webutils.http.BaseController;
import org.entcore.directory.scim.ScimJwtVerifier;

public class ScimController extends BaseController {

    private ScimJwtVerifier verifier;

    private ScimJwtVerifier verifier() {
        if (verifier == null) {
            verifier = new ScimJwtVerifier(vertx,
                    config.getString("scim-jwks-url", "http://localhost:8888/jwks.json"));
        }
        return verifier;
    }

    @Post("/scim/events")
    public void events(final HttpServerRequest request) {
        request.bodyHandler(buffer -> {
            final String jwt = buffer.toString().trim();
            verifier().verifyAndDecode(jwt).onSuccess(payload -> {
                final JsonObject action = new JsonObject().put("action", "scim-event").put("event", payload);
                eb.request("entcore.feeder", action,
                        new DeliveryOptions().setSendTimeout(30000L), ar -> {
                            if (ar.succeeded()) {
                                request.response().setStatusCode(202)
                                        .putHeader("content-type", "application/json")
                                        .end(new JsonObject().put("status", "accepted").encode());
                            } else {
                                request.response().setStatusCode(502).end("feeder.unavailable");
                            }
                        });
            }).onFailure(err ->
                    request.response().setStatusCode(401).end(String.valueOf(err.getMessage())));
        });
    }
}
