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

package org.entcore.auth.security;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

import static fr.wseduc.webutils.Utils.isEmpty;

/**
 * Défis à usage unique signés par les appareils enrôlés.
 *
 * <p>C'est le seul rempart contre le rejeu. Les horodatages de l'assertion n'y suffisent pas :
 * une assertion interceptée resterait rejouable pendant toute sa fenêtre de validité, et les
 * tablettes scolaires ont des horloges souvent fausses. Un défi tiré par le serveur, valable
 * une fois et peu de temps, ne dépend d'aucune horloge côté client.</p>
 *
 * <p>La consommation s'appuie sur {@code findAndModify} avec suppression : <b>la suppression
 * est le test d'unicité</b>. Relire puis supprimer laisserait passer deux requêtes
 * concurrentes, qui obtiendraient toutes deux le même défi — exactement le rejeu que l'on
 * cherche à interdire.</p>
 *
 * <p>Le ménage des défis jamais consommés est assuré par un index TTL sur {@code expireAt}
 * (cf. {@code migration/6.16.14/device_auth_indexes.js}). Cet index ne participe pas à la
 * correction : l'échéance est aussi contrôlée ici, car un index absent ou non encore créé ne
 * doit jamais se traduire par un défi éternellement valable.</p>
 */
public class DeviceChallengeStore {

    private static final Logger log = LoggerFactory.getLogger(DeviceChallengeStore.class);

    public static final String CHALLENGES_COLLECTION = "authdevicechallenges";

    /**
     * Durée de vie d'un défi. Assez longue pour laisser le temps d'un déverrouillage
     * biométrique hésitant, assez courte pour que la fenêtre d'un vol de défi reste étroite.
     */
    private static final long CHALLENGE_TTL_MS = 120_000L;

    /** 256 bits d'aléa : un défi devinable vaudrait absence de défi. */
    private static final int CHALLENGE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoDb mongo = MongoDb.getInstance();

    /**
     * Émet un défi pour un usage donné.
     *
     * @param purpose      usage auquel le défi est lié ({@code enrol}, {@code login}…) ; une
     *                     assertion produite pour un usage ne doit pas pouvoir en servir un autre
     * @param userId       compte concerné, ou {@code null} quand il n'est pas encore connu
     * @param credentialId clé attendue, ou {@code null} à l'enrôlement (elle n'existe pas encore)
     * @return le défi, à renvoyer tel quel dans la revendication {@code jti} de l'assertion
     */
    public Future<String> issue(final String purpose, final String userId, final String credentialId) {
        final String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        final long now = System.currentTimeMillis();
        final JsonObject document = new JsonObject()
                .put("_id", challenge)
                .put("purpose", purpose)
                .put("createdAt", now)
                .put("expiresAt", now + CHALLENGE_TTL_MS)
                .put("expireAt", MongoDb.toMongoDate(new Date(now + CHALLENGE_TTL_MS)));
        if (!isEmpty(userId)) {
            document.put("userId", userId);
        }
        if (!isEmpty(credentialId)) {
            document.put("credentialId", credentialId);
        }
        final Promise<String> promise = Promise.promise();
        mongo.insert(CHALLENGES_COLLECTION, document, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(challenge);
            } else {
                log.error("Error issuing device challenge : " + res.body().getString("message"));
                promise.fail("device.challenge.issue.error");
            }
        });
        return promise.future();
    }

    /**
     * Consomme un défi : il est supprimé, qu'il soit retenu ou non, et ne pourra donc jamais
     * resservir.
     *
     * @return le document du défi ({@code purpose}, {@code userId}, {@code credentialId})
     * @throws IllegalStateException via un échec {@code device.challenge.invalid} si le défi
     *                               est inconnu, déjà consommé, expiré, ou émis pour un autre usage
     */
    public Future<JsonObject> consume(final String challenge, final String purpose) {
        if (isEmpty(challenge) || isEmpty(purpose)) {
            return Future.failedFuture("device.challenge.invalid");
        }
        final Promise<JsonObject> promise = Promise.promise();
        // L'usage est dans le critère : un défi émis pour l'enrôlement ne peut pas être
        // présenté comme un défi de connexion, même par celui à qui il a été remis.
        final JsonObject matcher = new JsonObject()
                .put("_id", challenge)
                .put("purpose", purpose);
        mongo.findAndModify(CHALLENGES_COLLECTION, matcher, null, null, null, true, false, false, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                log.error("Error consuming device challenge : " + res.body().getString("message"));
                promise.fail("device.challenge.consume.error");
                return;
            }
            final JsonObject consumed = res.body().getJsonObject("result");
            if (consumed == null) {
                promise.fail("device.challenge.invalid");
                return;
            }
            final Long expiresAt = consumed.getLong("expiresAt");
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                promise.fail("device.challenge.invalid");
                return;
            }
            promise.complete(consumed);
        });
        return promise.future();
    }

    private static byte[] randomBytes() {
        final byte[] bytes = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

}
