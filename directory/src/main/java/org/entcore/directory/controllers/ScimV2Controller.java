/*
 * API SCIM 2.0 (RFC 7643/7644) : l'ENT comme fournisseur d'identités (service provider) pour un IAM
 * externe (Keycloak, Entra ID, Syncope, midPoint…).
 *
 *   GET  /scim/v2/ServiceProviderConfig | ResourceTypes | Schemas        (découverte, sans jeton)
 *   GET  /scim/v2/Users  ?filter=&startIndex=&count=     GET /scim/v2/Users/:id
 *   POST /scim/v2/Users   PUT|PATCH|DELETE /scim/v2/Users/:id
 *   GET  /scim/v2/Groups ?filter=&startIndex=&count=&excludedAttributes=members
 *   GET  /scim/v2/Groups/:id   POST /scim/v2/Groups   PATCH|DELETE /scim/v2/Groups/:id
 *
 * Accès machine à machine, authentifié par le serveur OAuth2 du module auth (voir ScimClientAuth) :
 * l'IAM est une application du registre en grant client_credentials, avec le scope scim:read et/ou
 * scim:write ; il obtient son jeton par POST /auth/oauth2/token et l'envoie en Bearer. Les routes
 * n'ont pas de @SecuredAction (pas de session ENT) : le jeton est contrôlé ici, y compris pour les
 * routes PATCH déclarées à la main. Désactivée tant que "scim-v2.enabled" n'est pas vrai.
 *
 * Configuration (module directory) :
 *   "scim-v2": { "enabled": true, "clients": ["<nom de l'application dans le registre>"],
 *                "base-url": "https://<ent>/directory/scim/v2", "max-results": 200 }
 */
package org.entcore.directory.controllers;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.security.SecuredAction;
import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.directory.scim.ScimFilter;
import org.entcore.directory.scim.ScimClientAuth;
import org.entcore.directory.scim.ScimMapper;
import org.entcore.directory.scim.ScimV2Service;
import org.vertx.java.core.http.RouteMatcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class ScimV2Controller extends BaseController {

    private static final String CONTENT_TYPE = "application/scim+json";
    private static final String READ = "scim:read";
    private static final String WRITE = "scim:write";

    private ScimV2Service service;
    private JsonArray schemas;

    /** web-utils n'a pas d'annotation @Patch : les routes PATCH sont déclarées ici. */
    @Override
    public void init(Vertx vertx, JsonObject config, RouteMatcher rm, Map<String, SecuredAction> securedActions) {
        super.init(vertx, config, rm, securedActions);
        rm.patch(pathPrefix + "/scim/v2/Users/:id", this::patchUser);
        rm.patch(pathPrefix + "/scim/v2/Groups/:id", this::patchGroup);
    }

    private JsonObject conf() {
        return config.getJsonObject("scim-v2", new JsonObject());
    }

    private ScimV2Service service() {
        if (service == null) service = new ScimV2Service(eb);
        return service;
    }

    private String baseUrl(HttpServerRequest request) {
        return conf().getString("base-url", getScheme(request) + "://" + getHost(request) + pathPrefix + "/scim/v2");
    }

    // ------------------------------------------------------------------------------- découverte

    @Get("/scim/v2/ServiceProviderConfig")
    public void serviceProviderConfig(HttpServerRequest request) {
        if (!enabled(request)) return;
        final int max = conf().getInteger("max-results", 200);
        respond(request, 200, new JsonObject()
                .put("schemas", new JsonArray().add("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"))
                .put("documentationUri", "https://open-ent.github.io/")
                .put("patch", new JsonObject().put("supported", true))
                .put("bulk", new JsonObject().put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0))
                .put("filter", new JsonObject().put("supported", true).put("maxResults", max))
                .put("changePassword", new JsonObject().put("supported", false))
                .put("sort", new JsonObject().put("supported", false))
                .put("etag", new JsonObject().put("supported", false))
                .put("authenticationSchemes", new JsonArray().add(new JsonObject()
                        .put("type", "oauthbearertoken").put("name", "OAuth Bearer Token").put("primary", true)
                        .put("description", "Jeton client_credentials délivré par /auth/oauth2/token (scopes scim:read, scim:write)")))
                .put("meta", new JsonObject().put("resourceType", "ServiceProviderConfig")
                        .put("location", baseUrl(request) + "/ServiceProviderConfig")));
    }

    @Get("/scim/v2/ResourceTypes")
    public void resourceTypes(HttpServerRequest request) {
        if (!enabled(request)) return;
        final String base = baseUrl(request);
        final JsonArray types = new JsonArray()
                .add(resourceType("User", "/Users", ScimMapper.USER, ScimMapper.EXT_USER, base))
                .add(resourceType("Group", "/Groups", ScimMapper.GROUP, ScimMapper.EXT_GROUP, base));
        respond(request, 200, ScimMapper.listResponse(types, types.size(), 1));
    }

    @Get("/scim/v2/Schemas")
    public void schemas(HttpServerRequest request) {
        if (!enabled(request)) return;
        final JsonArray all = loadSchemas();
        respond(request, 200, ScimMapper.listResponse(all, all.size(), 1));
    }

    private static JsonObject resourceType(String name, String endpoint, String schema, String extension, String base) {
        return new JsonObject()
                .put("schemas", new JsonArray().add("urn:ietf:params:scim:schemas:core:2.0:ResourceType"))
                .put("id", name).put("name", name).put("endpoint", endpoint).put("schema", schema)
                .put("schemaExtensions", new JsonArray().add(new JsonObject().put("schema", extension).put("required", false)))
                .put("meta", new JsonObject().put("resourceType", "ResourceType").put("location", base + "/ResourceTypes/" + name));
    }

    private JsonArray loadSchemas() {
        if (schemas == null) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("scim/schemas.json")) {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                final byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) bos.write(buf, 0, read);
                schemas = new JsonArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("[SCIM] unable to load scim/schemas.json", e);
                schemas = new JsonArray();
            }
        }
        return schemas;
    }

    // ------------------------------------------------------------------------------- Users

    @Get("/scim/v2/Users")
    public void listUsers(HttpServerRequest request) {
        authorize(request, READ, client -> {
            final int startIndex = Math.max(1, intParam(request, "startIndex", 1));
            final int count = Math.min(Math.max(0, intParam(request, "count", 100)), conf().getInteger("max-results", 200));
            final ScimFilter.Clause clause;
            try {
                clause = ScimFilter.toCypher(request.params().get("filter"), ScimMapper.USER_FILTER);
            } catch (ScimFilter.InvalidFilterException e) {
                respond(request, 400, ScimMapper.error(400, "invalidFilter", e.getMessage()));
                return;
            }
            final String base = baseUrl(request);
            service().listUsers(clause, startIndex, count).onComplete(ar -> {
                if (ar.failed()) {
                    fail(request, ar.cause());
                    return;
                }
                final JsonArray resources = new JsonArray();
                for (Object row : ar.result().getJsonArray("rows")) resources.add(ScimMapper.user((JsonObject) row, base));
                respond(request, 200, ScimMapper.listResponse(resources, ar.result().getLong("total"), startIndex));
            });
        });
    }

    @Get("/scim/v2/Users/:id")
    public void getUser(HttpServerRequest request) {
        authorize(request, READ, client -> sendUser(request, request.params().get("id"), 200));
    }

    @Post("/scim/v2/Users")
    public void createUser(HttpServerRequest request) {
        authorize(request, WRITE, client -> body(request, resource -> {
            final JsonObject name = resource.getJsonObject("name", new JsonObject());
            if (name.getString("givenName") == null || name.getString("familyName") == null) {
                respond(request, 400, ScimMapper.error(400, "invalidValue", "name.givenName and name.familyName are required"));
                return;
            }
            final String id = UUID.randomUUID().toString();
            write(request, "user-create", id, resource, client, 201);
        }));
    }

    @Put("/scim/v2/Users/:id")
    public void replaceUser(HttpServerRequest request) {
        authorize(request, WRITE, client -> body(request, resource ->
                write(request, "user-replace", request.params().get("id"), resource, client, 200)));
    }

    public void patchUser(HttpServerRequest request) {
        authorize(request, WRITE, client -> body(request, patch -> {
            if (!isPatchOp(patch)) {
                respond(request, 400, ScimMapper.error(400, "invalidSyntax", "PatchOp message expected"));
                return;
            }
            write(request, "user-patch", request.params().get("id"), patch, client, 200);
        }));
    }

    @Delete("/scim/v2/Users/:id")
    public void deleteUser(HttpServerRequest request) {
        authorize(request, WRITE, client -> write(request, "user-delete", request.params().get("id"), new JsonObject(), client, 204));
    }

    // ------------------------------------------------------------------------------ Groups

    @Get("/scim/v2/Groups")
    public void listGroups(HttpServerRequest request) {
        authorize(request, READ, client -> {
            final int startIndex = Math.max(1, intParam(request, "startIndex", 1));
            final int count = Math.min(Math.max(0, intParam(request, "count", 100)), conf().getInteger("max-results", 200));
            final ScimFilter.Clause clause;
            try {
                clause = ScimFilter.toCypher(request.params().get("filter"), ScimMapper.GROUP_FILTER);
            } catch (ScimFilter.InvalidFilterException e) {
                respond(request, 400, ScimMapper.error(400, "invalidFilter", e.getMessage()));
                return;
            }
            final boolean withMembers = withMembers(request);
            final String base = baseUrl(request);
            service().listGroups(clause, startIndex, count, withMembers).onComplete(ar -> {
                if (ar.failed()) {
                    fail(request, ar.cause());
                    return;
                }
                final JsonArray resources = new JsonArray();
                for (Object row : ar.result().getJsonArray("rows")) resources.add(ScimMapper.group((JsonObject) row, base, withMembers));
                respond(request, 200, ScimMapper.listResponse(resources, ar.result().getLong("total"), startIndex));
            });
        });
    }

    @Get("/scim/v2/Groups/:id")
    public void getGroup(HttpServerRequest request) {
        authorize(request, READ, client -> sendGroup(request, request.params().get("id"), 200));
    }

    @Post("/scim/v2/Groups")
    public void createGroup(HttpServerRequest request) {
        authorize(request, WRITE, client -> body(request, resource ->
                write(request, "group-create", UUID.randomUUID().toString(), resource, client, 201)));
    }

    public void patchGroup(HttpServerRequest request) {
        authorize(request, WRITE, client -> body(request, patch -> {
            if (!isPatchOp(patch)) {
                respond(request, 400, ScimMapper.error(400, "invalidSyntax", "PatchOp message expected"));
                return;
            }
            write(request, "group-patch", request.params().get("id"), patch, client, 200);
        }));
    }

    @Delete("/scim/v2/Groups/:id")
    public void deleteGroup(HttpServerRequest request) {
        authorize(request, WRITE, client -> write(request, "group-delete", request.params().get("id"), new JsonObject(), client, 204));
    }

    // ------------------------------------------------------------------------------ utilitaires

    /** Écriture via le feeder puis relecture de la ressource (ou 204 pour un DELETE). */
    private void write(HttpServerRequest request, String op, String id, JsonObject resource, String client, int status) {
        service().write(op, new JsonObject().put("id", id).put("resource", resource), client).onComplete(ar -> {
            // L'id renvoyé par le feeder prime : un create peut restaurer un compte existant.
            final String resourceId = ar.succeeded() ? ar.result().getString("id", id) : id;
            if (ar.failed()) {
                fail(request, ar.cause());
            } else if (status == 204) {
                request.response().setStatusCode(204).end();
            } else if (op.startsWith("group")) {
                sendGroup(request, resourceId, status);
            } else {
                sendUser(request, resourceId, status);
            }
        });
    }

    private void sendUser(HttpServerRequest request, String id, int status) {
        final String base = baseUrl(request);
        service().getUser(id).onComplete(ar -> {
            if (ar.failed()) fail(request, ar.cause());
            else if (ar.result() == null) respond(request, 404, ScimMapper.error(404, null, "User " + id + " not found"));
            else respondResource(request, status, ScimMapper.user(ar.result(), base));
        });
    }

    private void sendGroup(HttpServerRequest request, String id, int status) {
        final String base = baseUrl(request);
        final boolean withMembers = withMembers(request);
        service().getGroup(id, withMembers).onComplete(ar -> {
            if (ar.failed()) fail(request, ar.cause());
            else if (ar.result() == null) respond(request, 404, ScimMapper.error(404, null, "Group " + id + " not found"));
            else respondResource(request, status, ScimMapper.group(ar.result(), base, withMembers));
        });
    }

    private boolean enabled(HttpServerRequest request) {
        if (conf().getBoolean("enabled", false)) return true;
        respond(request, 404, ScimMapper.error(404, null, "SCIM API disabled"));
        return false;
    }

    /** Vérifie le jeton Bearer auprès du module auth et passe l'identifiant du client au handler. */
    private void authorize(HttpServerRequest request, String scope, Handler<String> handler) {
        if (!enabled(request)) return;
        final String authorization = request.headers().get("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            request.response().putHeader("WWW-Authenticate", "Bearer realm=\"scim\"");
            respond(request, 401, ScimMapper.error(401, null, "Bearer token required"));
            return;
        }
        request.pause();
        ScimClientAuth.validate(eb, authorization).onComplete(ar -> {
            request.resume();
            final String refused = ar.failed() ? ar.cause().getMessage()
                    : ScimClientAuth.check(ar.result(), scope, conf().getJsonArray("clients"));
            if (refused != null) {
                final boolean scopeError = "scim.token.scope".equals(refused);
                final boolean forbidden = scopeError || "scim.client.not.allowed".equals(refused);
                request.response().putHeader("WWW-Authenticate", scopeError
                        ? "Bearer error=\"insufficient_scope\", scope=\"" + scope + "\""
                        : "Bearer error=\"invalid_token\"");
                respond(request, forbidden ? 403 : 401, ScimMapper.error(forbidden ? 403 : 401, null, refused));
                return;
            }
            handler.handle(ar.result().getString("client_id"));
        });
    }

    private void body(HttpServerRequest request, Handler<JsonObject> handler) {
        request.bodyHandler(buffer -> {
            final JsonObject json;
            try {
                json = buffer.length() == 0 ? new JsonObject() : new JsonObject(buffer.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                respond(request, 400, ScimMapper.error(400, "invalidSyntax", "invalid JSON"));
                return;
            }
            handler.handle(json);
        });
    }

    private static boolean isPatchOp(JsonObject patch) {
        return patch.getJsonArray("schemas", new JsonArray()).contains(ScimMapper.PATCH_OP)
                && patch.getJsonArray("Operations") != null;
    }

    private static boolean withMembers(HttpServerRequest request) {
        final String excluded = request.params().get("excludedAttributes");
        return excluded == null || !excluded.toLowerCase().contains("members");
    }

    private static int intParam(HttpServerRequest request, String name, int defaultValue) {
        try {
            final String value = request.params().get(name);
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Traduit les erreurs du feeder (préfixes scim.*) en statuts HTTP SCIM. */
    private void fail(HttpServerRequest request, Throwable cause) {
        final String message = String.valueOf(cause.getMessage());
        if (message.startsWith("scim.conflict")) respond(request, 409, ScimMapper.error(409, "uniqueness", message));
        else if (message.startsWith("scim.notfound")) respond(request, 404, ScimMapper.error(404, null, message));
        else if (message.startsWith("scim.readonly")) respond(request, 403, ScimMapper.error(403, "mutability", message));
        else if (message.startsWith("scim.invalid")) respond(request, 400, ScimMapper.error(400, "invalidValue", message));
        else {
            log.error("[SCIM] " + request.method() + " " + request.path() + " : " + message);
            respond(request, 500, ScimMapper.error(500, null, "internal error"));
        }
    }

    private void respondResource(HttpServerRequest request, int status, JsonObject resource) {
        if (status == 201) request.response().putHeader("Location", resource.getJsonObject("meta").getString("location"));
        respond(request, status, resource);
    }

    private static void respond(HttpServerRequest request, int status, JsonObject body) {
        request.response().setStatusCode(status).putHeader("Content-Type", CONTENT_TYPE).end(body.encode());
    }
}
