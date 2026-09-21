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
import org.entcore.auth.services.DeviceService;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Appareils mémorisés dans MongoDB, un document par couple (utilisateur, appareil).
 *
 * <p>Le volume est borné par le nombre d'appareils d'une personne, pas par son activité :
 * on relit donc tous les appareils d'un utilisateur d'un coup, sans pagination.</p>
 */
public class MongoDbDeviceService implements DeviceService {

    private static final Logger log = LoggerFactory.getLogger(MongoDbDeviceService.class);

    public static final String DEVICES_COLLECTION = "authdevices";

    /**
     * Délai minimal entre deux alertes pour un même appareil. Un poste itinérant (4G, wifi
     * public) change d'IP plusieurs fois par jour : sans ce garde-fou l'alerte deviendrait
     * du bruit, et serait ignorée le jour où elle compte.
     */
    private static final long NOTIFY_THROTTLE_MS = 24 * 3600 * 1000L;

    private final MongoDb mongo = MongoDb.getInstance();

    @Override
    public Future<JsonObject> recordSighting(final String userId, final JsonObject clientInfos) {
        final JsonObject infos = clientInfos != null ? clientInfos : new JsonObject();
        final String deviceId = infos.getString("deviceId");
        if (isEmpty(userId) || isEmpty(deviceId)) {
            // Client sans identifiant d'appareil (application mobile, cookies bloqués) :
            // rien à mémoriser, et surtout aucune alerte — on serait incapable de
            // distinguer un nouvel appareil d'un appareil déjà connu.
            return Future.succeededFuture(new JsonObject()
                    .put("known", false)
                    .put("trusted", false)
                    .put("newIp", false)
                    .put("firstEver", false)
                    .put("shouldNotify", false));
        }
        final String ip = infos.getString("ip");
        final String prefix = ipPrefix(ip);
        final long now = System.currentTimeMillis();

        return devicesOf(userId).compose(devices -> {
            final JsonObject device = findById(devices, documentId(userId, deviceId));
            final boolean known = device != null;
            final boolean trusted = device != null && Boolean.TRUE.equals(device.getBoolean("trusted"));
            // Premier appareil jamais vu pour ce compte : c'est l'activation ou la première
            // connexion, alerter l'utilisateur de sa propre arrivée n'aurait aucun sens.
            final boolean firstEver = devices.isEmpty();
            final boolean newIp = known && isNotEmpty(prefix) && !knownPrefixes(device).contains(prefix);

            boolean shouldNotify = !firstEver && !trusted && (!known || newIp);
            if (shouldNotify && device != null) {
                final Long lastNotifiedAt = device.getLong("lastNotifiedAt");
                if (lastNotifiedAt != null && (now - lastNotifiedAt) < NOTIFY_THROTTLE_MS) {
                    shouldNotify = false;
                }
            }

            final JsonObject set = new JsonObject()
                    .put("userId", userId)
                    .put("deviceId", deviceId)
                    .put("lastSeen", now);
            if (isNotEmpty(infos.getString("ua"))) {
                set.put("ua", infos.getString("ua"));
            }
            if (isNotEmpty(ip)) {
                set.put("lastIp", ip);
            }
            final JsonObject setOnInsert = new JsonObject()
                    .put("firstSeen", now)
                    .put("trusted", false);
            if (isNotEmpty(ip)) {
                setOnInsert.put("firstIp", ip);
            }
            final JsonObject update = new JsonObject()
                    .put("$set", set)
                    .put("$setOnInsert", setOnInsert);
            if (isNotEmpty(prefix)) {
                update.put("$addToSet", new JsonObject().put("knownIpPrefixes", prefix));
            }

            final JsonObject result = new JsonObject()
                    .put("deviceId", deviceId)
                    .put("known", known)
                    .put("trusted", trusted)
                    .put("newIp", newIp)
                    .put("firstEver", firstEver)
                    .put("shouldNotify", shouldNotify);
            return upsert(documentId(userId, deviceId), update).map(v -> result);
        });
    }

    @Override
    public Future<JsonObject> devicesByIdFor(final String userId) {
        return devicesOf(userId).map(devices -> {
            final JsonObject byId = new JsonObject();
            for (Object o : devices) {
                if (!(o instanceof JsonObject)) continue;
                final JsonObject device = (JsonObject) o;
                final String deviceId = device.getString("deviceId");
                if (isNotEmpty(deviceId)) {
                    byId.put(deviceId, device);
                }
            }
            return byId;
        });
    }

    @Override
    public Future<Boolean> setTrusted(final String userId, final String deviceId, final boolean trusted) {
        if (isEmpty(userId) || isEmpty(deviceId)) {
            return Future.succeededFuture(false);
        }
        // Le userId est dans le critère et pas seulement dans l'identifiant : un appareil ne
        // peut être marqué de confiance que par le compte auquel il est rattaché.
        final JsonObject criteria = new JsonObject()
                .put("_id", documentId(userId, deviceId))
                .put("userId", userId);
        // L'existence est vérifiée séparément : la mise à jour ne compte que les documents
        // réellement modifiés, et marquer de confiance un appareil qui l'est déjà renverrait
        // zéro — donc « appareil introuvable », ce qui serait faux.
        return exists(criteria).compose(found -> {
            if (!found) {
                return Future.succeededFuture(false);
            }
            final Promise<Boolean> promise = Promise.promise();
            mongo.update(DEVICES_COLLECTION, criteria,
                    new JsonObject().put("$set", new JsonObject().put("trusted", trusted)), false, false, res -> {
                if ("ok".equals(res.body().getString("status"))) {
                    promise.complete(true);
                } else {
                    log.error("Error updating trust flag of device " + deviceId + " : " + res.body().getString("message"));
                    promise.fail("device.trust.error");
                }
            });
            return promise.future();
        });
    }

    @Override
    public Future<Void> markNotified(final String userId, final String deviceId) {
        if (isEmpty(userId) || isEmpty(deviceId)) {
            return Future.succeededFuture();
        }
        return upsert(documentId(userId, deviceId), new JsonObject()
                .put("$set", new JsonObject().put("lastNotifiedAt", System.currentTimeMillis())));
    }

    private Future<Boolean> exists(final JsonObject criteria) {
        final Promise<Boolean> promise = Promise.promise();
        mongo.findOne(DEVICES_COLLECTION, criteria, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(res.body().getJsonObject("result") != null);
            } else {
                log.error("Error looking up device " + criteria.encode() + " : " + res.body().getString("message"));
                promise.fail("device.lookup.error");
            }
        });
        return promise.future();
    }

    private Future<JsonArray> devicesOf(final String userId) {
        final Promise<JsonArray> promise = Promise.promise();
        mongo.find(DEVICES_COLLECTION, new JsonObject().put("userId", userId), res -> {
            final JsonArray devices = res.body().getJsonArray("results");
            if ("ok".equals(res.body().getString("status")) && devices != null) {
                promise.complete(devices);
            } else {
                log.error("Error listing devices of user " + userId + " : " + res.body().getString("message"));
                promise.fail("devices.list.error");
            }
        });
        return promise.future();
    }

    private Future<Void> upsert(final String id, final JsonObject update) {
        final Promise<Void> promise = Promise.promise();
        mongo.update(DEVICES_COLLECTION, new JsonObject().put("_id", id), update, true, false, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete();
            } else {
                log.error("Error upserting device " + id + " : " + res.body().getString("message"));
                promise.fail("device.upsert.error");
            }
        });
        return promise.future();
    }

    private static JsonObject findById(final JsonArray devices, final String id) {
        for (Object o : devices) {
            if (o instanceof JsonObject && id.equals(((JsonObject) o).getString("_id"))) {
                return (JsonObject) o;
            }
        }
        return null;
    }

    private static JsonArray knownPrefixes(final JsonObject device) {
        final JsonArray prefixes = device.getJsonArray("knownIpPrefixes");
        return prefixes != null ? prefixes : new JsonArray();
    }

    private static String documentId(final String userId, final String deviceId) {
        return userId + ":" + deviceId;
    }

    /**
     * Réseau d'origine, et non adresse exacte : une IP grand public change au gré des baux
     * DHCP, alerter sur le moindre changement d'adresse serait ininterrompu. On retient le
     * /24 en IPv4 et les quatre premiers groupes en IPv6.
     *
     * <p>Publique parce que c'est la règle qui décide si une connexion est inhabituelle :
     * elle mérite d'être couverte par des tests.</p>
     *
     * @return le préfixe, ou {@code null} si l'adresse est absente ou illisible
     */
    public static String ipPrefix(final String rawIp) {
        if (isEmpty(rawIp)) {
            return null;
        }
        // X-Forwarded-For peut enchaîner « client, proxy1, proxy2 » : seul le premier compte.
        String ip = rawIp.split(",")[0].trim();
        // Adresse IPv4 encapsulée en IPv6, forme courante derrière un ingress : ::ffff:10.0.0.1
        final int mapped = ip.lastIndexOf("::ffff:");
        if (mapped >= 0) {
            ip = ip.substring(mapped + "::ffff:".length());
        }
        if (ip.isEmpty()) {
            return null;
        }
        if (ip.indexOf('.') > 0) {
            final String[] octets = ip.split("\\.");
            return octets.length == 4 ? octets[0] + "." + octets[1] + "." + octets[2] : null;
        }
        if (ip.indexOf(':') >= 0) {
            final String[] groups = ip.split(":");
            final StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < groups.length && i < 4; i++) {
                if (i > 0) {
                    prefix.append(':');
                }
                prefix.append(groups[i]);
            }
            return prefix.length() > 0 ? prefix.toString() : null;
        }
        return null;
    }

}
