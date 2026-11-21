/* Copyright © "Open Digital Education", 2025
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.
 *
 */

package org.entcore.auth.services.impl;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.auth.services.DeviceCredentialService;

import static fr.wseduc.webutils.Utils.isEmpty;

/**
 * Clés d'appareil stockées dans MongoDB, un document par clé, {@code _id} étant l'identité
 * dérivée de la clé publique.
 *
 * <p>Cet identifiant n'est pas un choix d'implémentation anodin : la connexion par appareil
 * présente une clé <b>avant</b> que l'on sache à quel compte elle appartient, et doit donc
 * pouvoir la retrouver directement, sans parcourir la collection.</p>
 *
 * <p>Une clé n'est jamais supprimée, seulement marquée révoquée : savoir qu'une clé a existé,
 * quand elle a été retirée et par qui, fait partie de ce qu'on doit pouvoir reconstituer après
 * coup.</p>
 */
public class MongoDbDeviceCredentialService implements DeviceCredentialService {

    private static final Logger log = LoggerFactory.getLogger(MongoDbDeviceCredentialService.class);

    public static final String CREDENTIALS_COLLECTION = "authdevicekeys";

    private final MongoDb mongo = MongoDb.getInstance();

    @Override
    public Future<JsonObject> create(final JsonObject credential) {
        if (credential == null || isEmpty(credential.getString("_id"))) {
            return Future.failedFuture("device.key.invalid");
        }
        final Promise<JsonObject> promise = Promise.promise();
        mongo.insert(CREDENTIALS_COLLECTION, credential, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(credential);
                return;
            }
            final String message = res.body().getString("message", "");
            // L'identifiant étant l'empreinte de la clé, un doublon signifie que cette clé
            // exacte est déjà enrôlée — y compris sur un autre compte, ce qu'on refuse.
            if (message.contains("duplicate key") || message.contains("E11000")) {
                promise.fail("device.key.already.enrolled");
            } else {
                log.error("Error creating device credential : " + message);
                promise.fail("device.key.create.error");
            }
        });
        return promise.future();
    }

    @Override
    public Future<JsonObject> activeById(final String credentialId) {
        if (isEmpty(credentialId)) {
            return Future.succeededFuture(null);
        }
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findOne(CREDENTIALS_COLLECTION, activeMatcher(new JsonObject().put("_id", credentialId)), res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonObject("result"));
            } else {
                log.error("Error loading device credential : " + res.body().getString("message"));
                promise.fail("device.key.lookup.error");
            }
        });
        return promise.future();
    }

    @Override
    public Future<JsonArray> listActiveFor(final String userId) {
        if (isEmpty(userId)) {
            return Future.succeededFuture(new JsonArray());
        }
        final Promise<JsonArray> promise = Promise.promise();
        mongo.find(CREDENTIALS_COLLECTION, activeMatcher(new JsonObject().put("userId", userId)),
                new JsonObject().put("createdAt", -1), null, res -> {
            final JsonArray results = res.body().getJsonArray("results");
            if ("ok".equals(res.body().getString("status")) && results != null) {
                promise.complete(results);
            } else {
                log.error("Error listing device credentials of user " + userId + " : "
                        + res.body().getString("message"));
                promise.fail("device.keys.list.error");
            }
        });
        return promise.future();
    }

    @Override
    public Future<Boolean> revoke(final String userId, final String credentialId, final String revokedBy) {
        if (isEmpty(userId) || isEmpty(credentialId)) {
            return Future.succeededFuture(false);
        }
        // Le compte figure dans le critère, et pas seulement dans l'identifiant : une clé ne
        // peut être révoquée que depuis le compte auquel elle est rattachée.
        final JsonObject matcher = activeMatcher(new JsonObject()
                .put("_id", credentialId)
                .put("userId", userId));
        return revokeMatching(matcher, revokedBy).map(count -> count > 0);
    }

    @Override
    public Future<Integer> revokeForDevice(final String userId, final String deviceId, final String revokedBy) {
        if (isEmpty(userId) || isEmpty(deviceId)) {
            return Future.succeededFuture(0);
        }
        final JsonObject matcher = activeMatcher(new JsonObject()
                .put("userId", userId)
                .put("deviceId", deviceId));
        return revokeMatching(matcher, revokedBy);
    }

    @Override
    public Future<Void> markUsed(final String credentialId) {
        if (isEmpty(credentialId)) {
            return Future.succeededFuture();
        }
        final Promise<Void> promise = Promise.promise();
        final JsonObject update = new JsonObject()
                .put("$set", new JsonObject().put("lastUsedAt", System.currentTimeMillis()))
                .put("$inc", new JsonObject().put("usageCount", 1));
        mongo.update(CREDENTIALS_COLLECTION, new JsonObject().put("_id", credentialId), update, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                // Cette trace ne doit pas faire échouer l'authentification qui vient de réussir.
                log.warn("Unable to mark device credential " + credentialId + " as used : "
                        + res.body().getString("message"));
            }
            promise.complete();
        });
        return promise.future();
    }

    /**
     * Compte les clés visées puis les révoque. L'ordre importe : une mise à jour ne rend que le
     * nombre de documents réellement modifiés, et l'appelant a besoin de savoir si une clé
     * active existait, pas si la base a changé d'avis.
     */
    private Future<Integer> revokeMatching(final JsonObject matcher, final String revokedBy) {
        final Promise<Integer> promise = Promise.promise();
        mongo.count(CREDENTIALS_COLLECTION, matcher, countRes -> {
            if (!"ok".equals(countRes.body().getString("status"))) {
                log.error("Error counting device credentials : " + countRes.body().getString("message"));
                promise.fail("device.keys.revoke.error");
                return;
            }
            final int count = countRes.body().getInteger("count", 0);
            if (count == 0) {
                promise.complete(0);
                return;
            }
            final JsonObject set = new JsonObject().put("revokedAt", System.currentTimeMillis());
            if (!isEmpty(revokedBy)) {
                set.put("revokedBy", revokedBy);
            }
            mongo.update(CREDENTIALS_COLLECTION, matcher, new JsonObject().put("$set", set), false, true, res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    promise.complete(count);
                } else {
                    log.error("Error revoking device credentials : " + res.body().getString("message"));
                    promise.fail("device.keys.revoke.error");
                }
            });
        });
        return promise.future();
    }

    /** Restreint un critère aux clés non révoquées ({@code revokedAt} absent ou nul). */
    private static JsonObject activeMatcher(final JsonObject matcher) {
        return matcher.put("revokedAt", (Object) null);
    }

}
