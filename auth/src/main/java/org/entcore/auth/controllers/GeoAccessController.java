package org.entcore.auth.controllers;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.email.EmailFactory;
import org.entcore.common.geo.GeoAccessPolicies;
import org.entcore.common.geo.GeoAccessPolicy;
import org.entcore.common.geo.GeoIpLocator;
import org.entcore.common.http.request.JsonHttpServerRequest;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.vertx.java.core.http.RouteMatcher;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Zones géographiques autorisées pour se connecter, et carte des adresses IP de connexion.
 *
 * <p>Qui règle quoi :</p>
 * <ul>
 *   <li>super-admin : la politique de plateforme et celle de n'importe quel établissement ;</li>
 *   <li>ADML portant aussi {@code ADMIN_COLLECTIVITE} : la politique des établissements de son
 *   périmètre de collectivité (elle couvre l'établissement et ceux qui lui sont rattachés).</li>
 * </ul>
 *
 * <p>L'évaluation se fait dans le module session à la création de la session
 * ({@code AuthManager.applyGeoAccess}) ; ce contrôleur ne fait qu'administrer les politiques,
 * exposer les données de la carte et envoyer les alertes.</p>
 *
 * <p>Données de géolocalisation : DB-IP Lite (CC-BY 4.0), à créditer à l'affichage.</p>
 */
public class GeoAccessController extends BaseController {

    private static final String EVENTS = "events";
    private static final int MAX_DAYS = 366;
    private static final int MAX_IPS = 2000;
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "Personnel", "Teacher", "Student", "Relative", "Guest",
            GeoAccessPolicy.TARGET_ADMC, GeoAccessPolicy.TARGET_ADML));
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    private MongoDb mongo;

    @Override
    public void init(Vertx vertx, JsonObject config, RouteMatcher rm,
                     Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
        super.init(vertx, config, rm, securedActions);
        this.mongo = MongoDb.getInstance();
        final String geoipPath = config.getString("geoip-path");
        if (geoipPath != null) {
            GeoIpLocator.configure(geoipPath);
        }
        // Carte des connexions : recherche des LOGIN sur une période sans parcourir toute la collection
        mongo.command(new JsonObject().put("createIndexes", EVENTS).put("indexes", new JsonArray()
                .add(new JsonObject().put("name", "eventtype_date")
                        .put("key", new JsonObject().put("event-type", 1).put("date", -1)))).encode(),
                res -> {
                    if (!"ok".equals(res.body().getString("status"))) {
                        log.warn("Unable to create events index eventtype_date : " + res.body().encode());
                    }
                });
        mongo.command(new JsonObject().put("createIndexes", GeoAccessPolicies.EVENTS_COLLECTION).put("indexes", new JsonArray()
                .add(new JsonObject().put("name", "date").put("key", new JsonObject().put("date", -1)))).encode(),
                res -> { });
        // Un seul nœud traite chaque connexion hors zone (send, pas publish) : une alerte, pas une par instance.
        vertx.eventBus().<JsonObject>consumer(GeoAccessPolicies.OUT_OF_ZONE_ADDRESS, m -> alert(m.body()));
    }

    // ───────────────────────────── Droits ─────────────────────────────

    /** Établissements que l'utilisateur peut régler ({@code null} = tous, super-admin). */
    private static Set<String> collectivityScope(final UserInfos user) {
        if (user.isADMC()) {
            return null;
        }
        final Map<String, UserInfos.Function> functions = user.getFunctions();
        if (functions == null || !functions.containsKey(DefaultFunctions.ADMIN_LOCAL)) {
            return Collections.emptySet();
        }
        final UserInfos.Function collectivite = functions.get(DefaultFunctions.ADMIN_COLLECTIVITE);
        if (collectivite == null || collectivite.getScope() == null) {
            return Collections.emptySet();
        }
        // Le cumul est exigé, et seul le périmètre commun aux deux fonctions est réglable
        final Set<String> scope = new HashSet<>(collectivite.getScope());
        final List<String> adminLocal = functions.get(DefaultFunctions.ADMIN_LOCAL).getScope();
        scope.retainAll(adminLocal != null ? adminLocal : Collections.emptyList());
        return scope;
    }

    /** Établissements dont l'utilisateur peut consulter la carte ({@code null} = tous). */
    private static Set<String> viewScope(final UserInfos user) {
        if (user.isADMC()) {
            return null;
        }
        return AuthController.adminLocalScope(user);
    }

    private void withAdmin(final HttpServerRequest request, final io.vertx.core.Handler<UserInfos> handler) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null || (!user.isADMC() && !user.isADML())) {
                unauthorized(request);
                return;
            }
            handler.handle(user);
        });
    }

    // ───────────────────────────── Politiques ─────────────────────────────

    @Get("/admin/geo-access/policies")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void listPolicies(final HttpServerRequest request) {
        withAdmin(request, user -> {
            final Set<String> scope = collectivityScope(user);
            final JsonObject query = scope == null ? new JsonObject()
                    : new JsonObject().put("scope", GeoAccessPolicy.SCOPE_STRUCTURE)
                        .put("structureId", new JsonObject().put("$in", new JsonArray(new ArrayList<>(scope))));
            mongo.find(GeoAccessPolicy.COLLECTION, query, res -> {
                if (!"ok".equals(res.body().getString("status"))) {
                    renderError(request);
                    return;
                }
                renderJson(request, new JsonObject()
                        .put("policies", res.body().getJsonArray("results", new JsonArray()))
                        .put("canEditPlatform", user.isADMC())
                        .put("editableStructures", scope == null ? null : new JsonArray(new ArrayList<>(scope)))
                        .put("geoipAvailable", GeoIpLocator.getInstance().isAvailable()));
            });
        });
    }

    @Put("/admin/geo-access/policy/platform")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void savePlatformPolicy(final HttpServerRequest request) {
        UserUtils.getUserInfos(eb, request, user -> {
            if (user == null || !user.isADMC()) {
                unauthorized(request);
                return;
            }
            RequestUtils.bodyToJson(request, body -> {
                final JsonObject policy = sanitize(body, request);
                if (policy == null) {
                    return;
                }
                policy.put("_id", "platform").put("scope", GeoAccessPolicy.SCOPE_PLATFORM);
                save(request, user, policy);
            });
        });
    }

    @Put("/admin/geo-access/policy/structure/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void saveStructurePolicy(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        withAdmin(request, user -> {
            final Set<String> scope = collectivityScope(user);
            if (scope != null && !scope.contains(structureId)) {
                unauthorized(request);
                return;
            }
            RequestUtils.bodyToJson(request, body -> {
                final JsonObject policy = sanitize(body, request);
                if (policy == null) {
                    return;
                }
                coveredStructures(structureId).onComplete(ar -> {
                    if (ar.failed() || ar.result().isEmpty()) {
                        notFound(request, "structure.not.found");
                        return;
                    }
                    policy.put("_id", "structure:" + structureId)
                            .put("scope", GeoAccessPolicy.SCOPE_STRUCTURE)
                            .put("structureId", structureId)
                            .put("structureName", ar.result().getString("name"))
                            .put("structureIds", ar.result().getJsonArray("ids"));
                    save(request, user, policy);
                });
            });
        });
    }

    @Delete("/admin/geo-access/policy/structure/:structureId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void deleteStructurePolicy(final HttpServerRequest request) {
        final String structureId = request.params().get("structureId");
        withAdmin(request, user -> {
            final Set<String> scope = collectivityScope(user);
            if (scope != null && !scope.contains(structureId)) {
                unauthorized(request);
                return;
            }
            mongo.delete(GeoAccessPolicy.COLLECTION, new JsonObject().put("_id", "structure:" + structureId), res -> {
                GeoAccessPolicies.invalidate();
                renderJson(request, new JsonObject().put("deleted", structureId));
            });
        });
    }

    /** Établissement et ceux qui lui sont rattachés, à l'enregistrement de la politique. */
    private Future<JsonObject> coveredStructures(final String structureId) {
        final Promise<JsonObject> promise = Promise.promise();
        final String query = "MATCH (s:Structure {id: $id}) "
                + "OPTIONAL MATCH (s)<-[:HAS_ATTACHMENT*1..]-(child:Structure) "
                + "RETURN s.name AS name, s.id AS id, COLLECT(DISTINCT child.id) AS children";
        Neo4j.getInstance().execute(query, new JsonObject().put("id", structureId), res -> {
            final JsonArray rows = "ok".equals(res.body().getString("status")) ? res.body().getJsonArray("result") : null;
            if (rows == null || rows.isEmpty() || rows.getJsonObject(0).getString("id") == null) {
                promise.complete(new JsonObject());
                return;
            }
            final JsonObject row = rows.getJsonObject(0);
            final JsonArray ids = new JsonArray().add(structureId);
            for (Object c : row.getJsonArray("children", new JsonArray())) {
                if (c instanceof String && !ids.contains(c)) {
                    ids.add(c);
                }
            }
            promise.complete(new JsonObject().put("name", row.getString("name")).put("ids", ids));
        });
        return promise.future();
    }

    /** Valide et normalise une politique saisie ; répond 400 et renvoie {@code null} si invalide. */
    private JsonObject sanitize(final JsonObject body, final HttpServerRequest request) {
        if (body == null) {
            badRequest(request, "invalid.body");
            return null;
        }
        final String mode = body.getString("mode", GeoAccessPolicy.MODE_OBSERVE);
        if (!GeoAccessPolicy.MODE_OBSERVE.equals(mode) && !GeoAccessPolicy.MODE_MFA.equals(mode)) {
            badRequest(request, "invalid.mode");
            return null;
        }
        final JsonArray countries = new JsonArray();
        for (Object c : body.getJsonArray("allowedCountries", new JsonArray())) {
            if (!(c instanceof String) || !((String) c).matches("[A-Za-z]{2}")) {
                badRequest(request, "invalid.country");
                return null;
            }
            final String code = ((String) c).toUpperCase(Locale.ROOT);
            if (!countries.contains(code)) {
                countries.add(code);
            }
        }
        final JsonArray cidrs = new JsonArray();
        for (Object c : body.getJsonArray("allowedCidrs", new JsonArray())) {
            if (!(c instanceof String) || !GeoAccessPolicy.isValidCidr((String) c)) {
                badRequest(request, "invalid.cidr");
                return null;
            }
            cidrs.add(((String) c).trim());
        }
        final JsonArray profiles = new JsonArray();
        for (Object p : body.getJsonArray("profiles", new JsonArray())) {
            if (!(p instanceof String) || !TARGETS.contains(p)) {
                badRequest(request, "invalid.profile");
                return null;
            }
            profiles.add(p);
        }
        final JsonArray emails = new JsonArray();
        for (Object e : body.getJsonArray("alertEmails", new JsonArray())) {
            if (!(e instanceof String) || !((String) e).trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                badRequest(request, "invalid.email");
                return null;
            }
            emails.add(((String) e).trim());
        }
        final boolean enabled = Boolean.TRUE.equals(body.getBoolean("enabled"));
        if (enabled && countries.isEmpty() && cidrs.isEmpty()) {
            badRequest(request, "empty.zone");
            return null;
        }
        return new JsonObject()
                .put("enabled", enabled)
                .put("mode", mode)
                .put("allowedCountries", countries)
                .put("allowedCidrs", cidrs)
                .put("profiles", profiles)
                .put("alertEmails", emails);
    }

    private void save(final HttpServerRequest request, final UserInfos user, final JsonObject policy) {
        policy.put("updatedBy", new JsonObject().put("id", user.getUserId()).put("displayName", user.getUsername()))
                .put("updatedAt", MongoDb.now());
        mongo.save(GeoAccessPolicy.COLLECTION, policy, res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                renderError(request);
                return;
            }
            GeoAccessPolicies.invalidate();
            log.info("Geo access policy " + policy.getString("_id") + " saved by " + user.getUserId()
                    + " : " + policy.encode());
            renderJson(request, policy);
        });
    }

    /**
     * Simulation : où se situe cette adresse, et déclencherait-elle une politique pour ce
     * profil ? Sans paramètre {@code ip}, l'adresse de l'appelant.
     */
    @Get("/admin/geo-access/test")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void test(final HttpServerRequest request) {
        withAdmin(request, user -> {
            String ip = request.params().get("ip");
            if (ip == null || ip.trim().isEmpty()) {
                ip = GeoIpLocator.normalizeIp(fr.wseduc.webutils.http.Renders.getIp(request));
            }
            final String profile = request.params().get("profile");
            final String structureId = request.params().get("structureId");
            final JsonObject fakeSession = new JsonObject()
                    .put("type", profile)
                    .put("functions", new JsonObject())
                    .put("structures", structureId != null ? new JsonArray().add(structureId) : new JsonArray());
            if (GeoAccessPolicy.TARGET_ADMC.equals(profile)) {
                fakeSession.put("type", "Personnel").getJsonObject("functions").put("SUPER_ADMIN", new JsonObject());
            } else if (GeoAccessPolicy.TARGET_ADML.equals(profile)) {
                fakeSession.put("type", "Personnel").getJsonObject("functions").put("ADMIN_LOCAL", new JsonObject());
            }
            final String finalIp = ip;
            GeoAccessPolicies.active().onComplete(ar -> {
                final JsonObject geo = GeoIpLocator.getInstance().locate(finalIp);
                final JsonObject decision = GeoAccessPolicy.evaluate(
                        ar.succeeded() ? ar.result() : Collections.emptyList(), fakeSession, finalIp, geo);
                decision.put("geoipAvailable", GeoIpLocator.getInstance().isAvailable());
                renderJson(request, decision);
            });
        });
    }

    // ───────────────────────────── Carte ─────────────────────────────

    /**
     * Adresses IP des connexions réussies (événements {@code LOGIN}) sur une période,
     * regroupées par adresse et géolocalisées. ADML : établissements de son périmètre.
     * Paramètres : {@code days} (défaut 30, max 366), {@code structureId}, {@code profile}.
     */
    @Get("/admin/geo-access/logins")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void logins(final HttpServerRequest request) {
        withAdmin(request, user -> {
            int days;
            try {
                days = Math.max(1, Math.min(MAX_DAYS, Integer.parseInt(request.params().get("days"))));
            } catch (Exception e) {
                days = 30;
            }
            final long from = System.currentTimeMillis() - days * 24L * 3600 * 1000;
            final JsonObject match = new JsonObject()
                    .put("event-type", "LOGIN")
                    .put("date", new JsonObject().put("$gte", from))
                    .put("ip", new JsonObject().put("$exists", true));
            final Set<String> scope = viewScope(user);
            final String structureId = request.params().get("structureId");
            if (structureId != null && !structureId.isEmpty()) {
                if (scope != null && !scope.contains(structureId)) {
                    unauthorized(request);
                    return;
                }
                match.put("structures", structureId);
            } else if (scope != null) {
                match.put("structures", new JsonObject().put("$in", new JsonArray(new ArrayList<>(scope))));
            }
            final String profile = request.params().get("profile");
            if (profile != null && !profile.isEmpty()) {
                match.put("profil", profile);
            }
            final JsonArray pipeline = new JsonArray()
                    .add(new JsonObject().put("$match", match))
                    .add(new JsonObject().put("$group", new JsonObject()
                            .put("_id", "$ip")
                            .put("count", new JsonObject().put("$sum", 1))
                            .put("users", new JsonObject().put("$addToSet", "$userId"))
                            .put("profiles", new JsonObject().put("$addToSet", "$profil"))
                            .put("first", new JsonObject().put("$min", "$date"))
                            .put("last", new JsonObject().put("$max", "$date"))))
                    .add(new JsonObject().put("$sort", new JsonObject().put("count", -1)))
                    .add(new JsonObject().put("$limit", MAX_IPS))
                    .add(new JsonObject().put("$project", new JsonObject()
                            .put("count", 1).put("profiles", 1).put("first", 1).put("last", 1)
                            .put("userCount", new JsonObject().put("$size", "$users"))
                            .put("users", new JsonObject().put("$slice", new JsonArray().add("$users").add(10)))));
            final JsonObject command = new JsonObject()
                    .put("aggregate", EVENTS)
                    .put("allowDiskUse", true)
                    .put("pipeline", pipeline)
                    .put("cursor", new JsonObject().put("batchSize", MAX_IPS));
            final int finalDays = days;
            mongo.command(command.encode(), new DeliveryOptions().setSendTimeout(60_000L), res -> {
                if (!"ok".equals(res.body().getString("status"))) {
                    log.error("Login map aggregation failed : " + res.body().encode());
                    renderError(request);
                    return;
                }
                final JsonArray rows = res.body().getJsonObject("result", new JsonObject())
                        .getJsonObject("cursor", new JsonObject()).getJsonArray("firstBatch", new JsonArray());
                GeoAccessPolicies.active().onComplete(pol -> {
                    final List<JsonObject> policies = pol.succeeded() ? pol.result() : Collections.emptyList();
                    renderJson(request, new JsonObject()
                            .put("days", finalDays)
                            .put("geoipAvailable", GeoIpLocator.getInstance().isAvailable())
                            .put("attribution", "IP Geolocation by DB-IP (https://db-ip.com)")
                            .put("ips", mergeAndLocate(rows, policies)));
                });
            });
        });
    }

    /**
     * Regroupe les entrées dont l'en-tête X-Forwarded-For brut différait (« client, proxy »)
     * mais dont l'adresse cliente est la même, puis les géolocalise. {@code outOfZone} indique
     * si l'adresse serait hors zone pour la politique de plateforme (tous profils).
     */
    static JsonArray mergeAndLocate(final JsonArray rows, final List<JsonObject> policies) {
        final Map<String, JsonObject> byIp = new LinkedHashMap<>();
        for (Object o : rows) {
            if (!(o instanceof JsonObject)) continue;
            final JsonObject row = (JsonObject) o;
            final String ip = GeoIpLocator.normalizeIp(row.getString("_id"));
            if (ip == null) continue;
            final JsonObject current = byIp.get(ip);
            if (current == null) {
                final JsonObject entry = new JsonObject().put("ip", ip)
                        .put("count", row.getInteger("count", 0))
                        .put("userCount", row.getInteger("userCount", 0))
                        .put("users", row.getJsonArray("users", new JsonArray()))
                        .put("profiles", row.getJsonArray("profiles", new JsonArray()))
                        .put("first", row.getValue("first"))
                        .put("last", row.getValue("last"));
                byIp.put(ip, entry);
            } else {
                current.put("count", current.getInteger("count") + row.getInteger("count", 0));
                current.put("userCount", current.getInteger("userCount") + row.getInteger("userCount", 0));
                for (Object u : row.getJsonArray("users", new JsonArray())) {
                    if (!current.getJsonArray("users").contains(u) && current.getJsonArray("users").size() < 10) {
                        current.getJsonArray("users").add(u);
                    }
                }
                if (row.getValue("last") instanceof Number && current.getValue("last") instanceof Number
                        && row.getLong("last") > current.getLong("last")) {
                    current.put("last", row.getLong("last"));
                }
            }
        }
        JsonObject platform = null;
        for (JsonObject p : policies) {
            if (GeoAccessPolicy.SCOPE_PLATFORM.equals(p.getString("scope"))) {
                platform = p;
            }
        }
        final GeoIpLocator locator = GeoIpLocator.getInstance();
        final JsonArray result = new JsonArray();
        for (JsonObject entry : byIp.values()) {
            final JsonObject geo = locator.locate(entry.getString("ip"));
            if (geo != null) {
                for (String k : new String[]{"country", "countryName", "region", "city", "lat", "lon", "private"}) {
                    if (geo.containsKey(k)) {
                        entry.put(k, geo.getValue(k));
                    }
                }
            }
            if (platform != null) {
                final Boolean out = GeoAccessPolicy.isOutOfZone(platform, entry.getString("ip"), geo);
                if (out != null) {
                    entry.put("outOfZone", out);
                }
            }
            result.add(entry);
        }
        return result;
    }

    /** Journal des connexions hors zone (plus récentes d'abord). ADML : son périmètre. */
    @Get("/admin/geo-access/events")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void events(final HttpServerRequest request) {
        withAdmin(request, user -> {
            final Set<String> scope = viewScope(user);
            final JsonObject query = scope == null ? new JsonObject()
                    : new JsonObject().put("structures", new JsonObject().put("$in", new JsonArray(new ArrayList<>(scope))));
            mongo.find(GeoAccessPolicies.EVENTS_COLLECTION, query,
                    new JsonObject().put("date", -1), null, 0, 200, 200, res -> {
                if (!"ok".equals(res.body().getString("status"))) {
                    renderError(request);
                    return;
                }
                renderJson(request, new JsonObject().put("events", res.body().getJsonArray("results", new JsonArray())));
            });
        });
    }

    // ───────────────────────────── Alertes ─────────────────────────────

    /**
     * Courriel aux destinataires d'alerte des politiques qui ont placé la connexion hors zone.
     * Les adresses sont saisies dans la politique : pas de recherche d'administrateurs,
     * le réglage dit explicitement qui prévenir.
     */
    private void alert(final JsonObject event) {
        final JsonArray policyIds = event.getJsonArray("policies", new JsonArray());
        if (policyIds.isEmpty()) {
            return;
        }
        mongo.find(GeoAccessPolicy.COLLECTION,
                new JsonObject().put("_id", new JsonObject().put("$in", policyIds)), res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                return;
            }
            final Set<String> recipients = new LinkedHashSet<>();
            for (Object o : res.body().getJsonArray("results", new JsonArray())) {
                if (o instanceof JsonObject) {
                    for (Object e : ((JsonObject) o).getJsonArray("alertEmails", new JsonArray())) {
                        if (e instanceof String) {
                            recipients.add((String) e);
                        }
                    }
                }
            }
            final EmailSender sender = EmailFactory.getInstance().getSender();
            if (recipients.isEmpty() || sender == null) {
                return;
            }
            final String host = config.getString("host", "");
            String domain = "localhost";
            String scheme = "https";
            try {
                final URI uri = URI.create(host);
                if (uri.getHost() != null) {
                    domain = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                    scheme = uri.getScheme();
                }
            } catch (Exception ignored) {
                // hôte non configuré : liens relatifs
            }
            final HttpServerRequest fakeRequest = new JsonHttpServerRequest(new JsonObject()
                    .put("headers", new JsonObject().put("Host", domain).put("X-Forwarded-Proto", scheme)
                            .put("Accept-Language", "fr")));
            final JsonObject geo = event.getJsonObject("geo", new JsonObject());
            final String location = String.join(", ", nonEmpty(geo.getString("city"), geo.getString("region"),
                    geo.getString("countryName"), geo.getString("country")));
            final boolean enforced = Boolean.TRUE.equals(event.getBoolean("enforced"));
            final boolean reachable = Boolean.TRUE.equals(event.getBoolean("mfaReachable"));
            final JsonObject params = new JsonObject()
                    .put("displayName", event.getString("displayName", ""))
                    .put("login", event.getString("login", ""))
                    .put("profile", event.getString("profile", ""))
                    .put("ip", event.getString("ip", ""))
                    .put("location", location.isEmpty() ? "inconnue" : location)
                    .put("ua", event.getString("ua", ""))
                    .put("date", DATE_FORMAT.format(Instant.ofEpochMilli(event.getLong("date", System.currentTimeMillis())).atZone(ZONE)))
                    .put("measure", enforced
                            ? (reachable ? "Double authentification exigée avant tout accès."
                                         : "Double authentification demandée, mais le compte n'a aucun moyen de la réaliser (ni e-mail, ni application, ni appareil enrôlé) : accès accordé sans second facteur.")
                            : "Mode observation : aucune mesure, connexion simplement signalée.")
                    .put("mapUrl", scheme + "://" + domain + "/dashboard/admin/securite/zones-connexion")
                    .put("userUrl", scheme + "://" + domain + "/dashboard/admin/user/" + event.getString("userId", ""));
            for (String to : recipients) {
                sender.sendEmail(fakeRequest, to, null, null, "Connexion hors zone autorisée : "
                        + event.getString("displayName", event.getString("login", "")),
                        "email/geoAccessAlert.html", params, false, ar -> {
                    if (ar.failed()) {
                        log.error("Error sending geo access alert to " + to, ar.cause());
                    }
                });
            }
        });
    }

    private static List<String> nonEmpty(final String... values) {
        final List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.trim().isEmpty() && !out.contains(v)) {
                out.add(v);
                if (out.size() == 3) break;
            }
        }
        return out;
    }
}
