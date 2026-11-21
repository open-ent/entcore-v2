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
import fr.wseduc.webutils.security.BCrypt;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.auth.services.DeviceTicketService;
import org.entcore.common.validation.StringValidation;

import java.util.Date;

import static fr.wseduc.webutils.Utils.isEmpty;

/**
 * Tickets d'enrôlement délivrés par un administrateur, stockés dans MongoDB.
 *
 * <p>Collection dédiée plutôt que la propriété {@code u.otp} du compte, qui sert déjà de mot de
 * passe à usage unique : deux mécanismes qui partagent un même champ finissent par s'effacer
 * l'un l'autre, au pire moment.</p>
 *
 * <p>Le code n'est jamais conservé en clair. Il est affiché une seule fois à l'administrateur
 * qui l'a demandé, et ne peut plus être relu ensuite — pas même par lui.</p>
 */
public class MongoDbDeviceTicketService implements DeviceTicketService {

    private static final Logger log = LoggerFactory.getLogger(MongoDbDeviceTicketService.class);

    public static final String TICKETS_COLLECTION = "authdevicetickets";

    /**
     * Durée de vie d'un ticket. Assez longue pour aller trouver l'élève et lui faire sortir son
     * téléphone, assez courte pour qu'un code noté sur un papier oublié ne vaille plus rien.
     */
    private static final long TICKET_TTL_MS = 15 * 60_000L;

    /** Longueur du code, sur l'alphabet sans ambiguïté d'entcore (ni i, l, o, q, u, ni 0, 1, 2). */
    private static final int TICKET_CODE_LENGTH = 8;

    private final MongoDb mongo = MongoDb.getInstance();

    @Override
    public Future<JsonObject> issue(final String userId, final String issuedBy) {
        if (isEmpty(userId) || isEmpty(issuedBy)) {
            return Future.failedFuture("device.ticket.invalid");
        }
        final String code = StringValidation.generateRandomCode(TICKET_CODE_LENGTH);
        final long now = System.currentTimeMillis();
        final long expiresAt = now + TICKET_TTL_MS;
        // Un seul ticket valable à la fois par compte : en délivrer un neuf annule le précédent,
        // sans quoi un code oublié resterait utilisable en parallèle.
        final JsonObject document = new JsonObject()
                .put("_id", userId)
                .put("codeHash", BCrypt.hashpw(code, BCrypt.gensalt()))
                .put("issuedBy", issuedBy)
                .put("issuedAt", now)
                .put("expiresAt", expiresAt)
                .put("expireAt", MongoDb.toMongoDate(new Date(expiresAt)));
        final Promise<JsonObject> promise = Promise.promise();
        mongo.update(TICKETS_COLLECTION, new JsonObject().put("_id", userId),
                new JsonObject().put("$set", document), true, false, res -> {
            if ("ok".equals(res.body().getString("status"))) {
                promise.complete(new JsonObject()
                        .put("code", code)
                        .put("expiresAt", expiresAt)
                        .put("expiresIn", TICKET_TTL_MS / 1000));
            } else {
                log.error("Error issuing device enrolment ticket : " + res.body().getString("message"));
                promise.fail("device.ticket.issue.error");
            }
        });
        return promise.future();
    }

    @Override
    public Future<Boolean> consume(final String userId, final String code) {
        if (isEmpty(userId) || isEmpty(code)) {
            return Future.succeededFuture(false);
        }
        final Promise<Boolean> promise = Promise.promise();
        // Le ticket est retiré quel que soit le verdict : un code erroné le consomme aussi, ce
        // qui interdit de le deviner par essais successifs. L'administrateur en redélivre un.
        mongo.findAndModify(TICKETS_COLLECTION, new JsonObject().put("_id", userId),
                null, null, null, true, false, false, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                log.error("Error consuming device enrolment ticket : " + res.body().getString("message"));
                promise.fail("device.ticket.consume.error");
                return;
            }
            final JsonObject ticket = res.body().getJsonObject("result");
            if (ticket == null) {
                promise.complete(false);
                return;
            }
            final Long expiresAt = ticket.getLong("expiresAt");
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                promise.complete(false);
                return;
            }
            final String hash = ticket.getString("codeHash");
            promise.complete(hash != null && BCrypt.checkpw(code, hash));
        });
        return promise.future();
    }

}
