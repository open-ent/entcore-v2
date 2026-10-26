/*
 * Consultation du journal d'audit des opérations sensibles (super administrateur) :
 *
 *   GET /infra/audit?actorId=&targetId=&action=&source=&from=<epoch ms>&to=<epoch ms>&page=0&size=50
 *       Entrées les plus récentes d'abord : {total, entries}.
 *   GET /infra/audit/verify?fromSeq=0
 *       Contrôle d'intégrité de la chaîne : {valid, checked, anomalies[{seq, reason}]}.
 *
 * Lecture seule : l'écriture passe exclusivement par MongoDbAuditStore (bus entcore.audit).
 */
package org.entcore.infra.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.audit.AuditHelper;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.infra.services.impl.MongoDbAuditStore;

import java.util.regex.Pattern;

public class AuditController extends BaseController {

    private static final int MAX_SIZE = 200;

    @Get("/audit")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void list(final HttpServerRequest request) {
        final JsonObject query = buildQuery(request);
        final int size = Math.min(MAX_SIZE, Math.max(1, intParam(request, "size", 50)));
        final int page = Math.max(0, intParam(request, "page", 0));
        final MongoDb mongo = MongoDb.getInstance();
        mongo.count(MongoDbAuditStore.COLLECTION, query, count -> {
            if (!"ok".equals(count.body().getString("status"))) {
                renderError(request, count.body());
                return;
            }
            mongo.find(MongoDbAuditStore.COLLECTION, query, new JsonObject().put("seq", -1),
                    new JsonObject().put("_id", 0), page * size, size, size, res -> {
                if (!"ok".equals(res.body().getString("status"))) {
                    renderError(request, res.body());
                    return;
                }
                renderJson(request, new JsonObject()
                        .put("total", count.body().getLong("count", 0L))
                        .put("entries", res.body().getJsonArray("results", new JsonArray())));
            });
        });
    }

    @Get("/audit/verify")
    @SecuredAction(value = "", type = ActionType.RESOURCE)
    @ResourceFilter(SuperAdminFilter.class)
    @MfaProtected()
    public void verify(final HttpServerRequest request) {
        final JsonObject message = new JsonObject().put("action", "verify")
                .put("fromSeq", (long) Math.max(0, intParam(request, "fromSeq", 0)));
        eb.request(AuditHelper.ADDRESS, message, new DeliveryOptions().setSendTimeout(120_000L), ar -> {
            if (ar.failed()) {
                renderError(request, new JsonObject().put("error", ar.cause().getMessage()));
                return;
            }
            final JsonObject body = (JsonObject) ar.result().body();
            if ("ok".equals(body.getString("status"))) renderJson(request, body);
            else renderError(request, body);
        });
    }

    /** Filtres exacts (identifiants) ou préfixe insensible à la casse (action). */
    static JsonObject buildQuery(HttpServerRequest request) {
        final JsonObject query = new JsonObject();
        final String actorId = request.params().get("actorId");
        if (actorId != null && !actorId.isEmpty()) query.put("actor.id", actorId);
        final String targetId = request.params().get("targetId");
        if (targetId != null && !targetId.isEmpty()) query.put("target.id", targetId);
        final String source = request.params().get("source");
        if (source != null && !source.isEmpty()) query.put("source", source);
        final String action = request.params().get("action");
        if (action != null && !action.isEmpty()) {
            query.put("action", new JsonObject().put("$regex", "^" + Pattern.quote(action)).put("$options", "i"));
        }
        final JsonObject date = new JsonObject();
        final Long from = longParam(request, "from");
        final Long to = longParam(request, "to");
        if (from != null) date.put("$gte", from);
        if (to != null) date.put("$lt", to);
        if (!date.isEmpty()) query.put("date", date);
        return query;
    }

    private static int intParam(HttpServerRequest request, String name, int defaultValue) {
        final Long v = longParam(request, name);
        return v == null ? defaultValue : v.intValue();
    }

    private static Long longParam(HttpServerRequest request, String name) {
        try {
            final String v = request.params().get(name);
            return v == null || v.isEmpty() ? null : Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
