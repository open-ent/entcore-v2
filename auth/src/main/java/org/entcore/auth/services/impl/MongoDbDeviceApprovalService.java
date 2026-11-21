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
import org.entcore.auth.services.DeviceApprovalService;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Demandes d'approbation stockées dans MongoDB, une par connexion web à confirmer.
 *
 * <p>Durée de vie courte et index TTL : ces documents n'ont de sens que le temps qu'une personne
 * sorte son téléphone.</p>
 */
public class MongoDbDeviceApprovalService implements DeviceApprovalService {

    private static final Logger log = LoggerFactory.getLogger(MongoDbDeviceApprovalService.class);

    public static final String APPROVALS_COLLECTION = "authdeviceapprovals";

    /** Le temps de sortir son téléphone, de le déverrouiller et de lire un nombre. */
    private static final long APPROVAL_TTL_MS = 5 * 60_000L;

    /** Nombres proposés sur le téléphone, dont un seul figure sur l'écran web. */
    private static final int NUMBER_CHOICES = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoDb mongo = MongoDb.getInstance();

    @Override
    public Future<JsonObject> open(final String userId, final String sessionId,
                                   final String ip, final String ua) {
        if (isEmpty(userId) || isEmpty(sessionId)) {
            return Future.failedFuture("device.approval.invalid");
        }
        final String approvalId = UUID.randomUUID().toString();
        final String code = sixDigits();
        final int expected = twoDigits();
        final JsonArray numbers = decoysAround(expected);
        final long now = System.currentTimeMillis();
        final long expiresAt = now + APPROVAL_TTL_MS;

        final JsonObject document = new JsonObject()
                .put("_id", approvalId)
                .put("userId", userId)
                .put("sessionId", sessionId)
                .put("code", code)
                .put("expectedNumber", expected)
                .put("numbers", numbers)
                .put("state", STATE_PENDING)
                .put("createdAt", now)
                .put("expiresAt", expiresAt)
                .put("expireAt", MongoDb.toMongoDate(new Date(expiresAt)));
        if (isNotEmpty(ip)) {
            document.put("ip", ip);
        }
        if (isNotEmpty(ua)) {
            document.put("ua", ua);
        }

        final Promise<JsonObject> promise = Promise.promise();
        mongo.insert(APPROVALS_COLLECTION, document, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                // Le nombre attendu n'est pas rendu : l'écran web montre les trois nombres sans
                // désigner le bon, c'est au téléphone de le faire choisir.
                promise.complete(new JsonObject()
                        .put("approvalId", approvalId)
                        .put("code", code)
                        .put("numbers", numbers)
                        .put("expiresIn", APPROVAL_TTL_MS / 1000));
            } else {
                log.error("Error opening device approval : " + res.body().getString("message"));
                promise.fail("device.approval.open.error");
            }
        });
        return promise.future();
    }

    @Override
    public Future<JsonObject> state(final String approvalId) {
        if (isEmpty(approvalId)) {
            return Future.failedFuture("device.approval.unknown");
        }
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findOne(APPROVALS_COLLECTION, new JsonObject().put("_id", approvalId), res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                log.error("Error reading device approval : " + res.body().getString("message"));
                promise.fail("device.approval.read.error");
                return;
            }
            final JsonObject approval = res.body().getJsonObject("result");
            if (approval == null || expired(approval)) {
                // Document purgé par l'index TTL, ou échéance dépassée : même réponse, l'écran
                // web doit simplement cesser d'attendre.
                promise.complete(new JsonObject().put("state", "expired"));
                return;
            }
            promise.complete(new JsonObject()
                    .put("state", approval.getString("state"))
                    .put("expiresAt", approval.getLong("expiresAt")));
        });
        return promise.future();
    }

    @Override
    public Future<JsonObject> findPendingByCode(final String userId, final String code) {
        if (isEmpty(userId) || isEmpty(code)) {
            return Future.succeededFuture(null);
        }
        final JsonObject matcher = new JsonObject()
                // Le compte fait partie du critère : un code ne vaut que pour les demandes
                // ouvertes par le compte qui le présente.
                .put("userId", userId)
                .put("code", code.trim())
                .put("state", STATE_PENDING);
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findOne(APPROVALS_COLLECTION, matcher, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                log.error("Error looking up device approval : " + res.body().getString("message"));
                promise.fail("device.approval.read.error");
                return;
            }
            final JsonObject approval = res.body().getJsonObject("result");
            promise.complete(approval != null && !expired(approval) ? approval : null);
        });
        return promise.future();
    }

    @Override
    public Future<JsonObject> decide(final String approvalId, final String userId, final int chosenNumber) {
        if (isEmpty(approvalId) || isEmpty(userId)) {
            return Future.failedFuture("device.approval.unknown");
        }
        // Transition atomique depuis « en attente » : deux téléphones ne peuvent pas trancher la
        // même demande, et une demande déjà tranchée ne se rejoue pas.
        final JsonObject matcher = new JsonObject()
                .put("_id", approvalId)
                .put("userId", userId)
                .put("state", STATE_PENDING);
        final Promise<JsonObject> promise = Promise.promise();
        mongo.findAndModify(APPROVALS_COLLECTION, matcher,
                new JsonObject().put("$set", new JsonObject().put("state", "deciding")),
                null, null, false, false, false, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                log.error("Error deciding device approval : " + res.body().getString("message"));
                promise.fail("device.approval.decide.error");
                return;
            }
            // `returnNew` est faux : on reçoit le document tel qu'il était avant la transition,
            // donc avec le nombre attendu, et on sait qu'on est seul à l'avoir pris.
            final JsonObject approval = res.body().getJsonObject("result");
            if (approval == null || expired(approval)) {
                promise.fail("device.approval.unknown");
                return;
            }
            final boolean right = approval.getInteger("expectedNumber", -1) == chosenNumber;
            final String decided = right ? STATE_APPROVED : STATE_DENIED;
            mongo.update(APPROVALS_COLLECTION, new JsonObject().put("_id", approvalId),
                    new JsonObject().put("$set", new JsonObject()
                            .put("state", decided)
                            .put("decidedAt", System.currentTimeMillis())), updateRes -> {
                if (!"ok".equals(updateRes.body().getString("status"))) {
                    log.error("Error recording device approval decision : "
                            + updateRes.body().getString("message"));
                    promise.fail("device.approval.decide.error");
                    return;
                }
                promise.complete(approval.copy().put("state", decided));
            });
        });
        return promise.future();
    }

    private static boolean expired(final JsonObject approval) {
        final Long expiresAt = approval.getLong("expiresAt");
        return expiresAt == null || expiresAt < System.currentTimeMillis();
    }

    /** Code affiché sur l'écran web, à recopier dans l'application. */
    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Nombre à deux chiffres, lisible d'un coup d'œil sur l'écran de la connexion. */
    private static int twoDigits() {
        return 10 + RANDOM.nextInt(90);
    }

    /**
     * Les trois nombres proposés, mélangés, le bon parmi deux leurres distincts. Les leurres ne
     * sont pas choisis près du bon : des nombres voisins se confondraient d'un coup d'œil, et
     * c'est précisément le coup d'œil qu'on cherche à obtenir.
     */
    private static JsonArray decoysAround(final int expected) {
        final List<Integer> choices = new ArrayList<>(NUMBER_CHOICES);
        choices.add(expected);
        while (choices.size() < NUMBER_CHOICES) {
            final int candidate = twoDigits();
            boolean tooClose = false;
            for (Integer existing : choices) {
                if (Math.abs(existing - candidate) < 10) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                choices.add(candidate);
            }
        }
        Collections.shuffle(choices, RANDOM);
        return new JsonArray(choices);
    }

}
