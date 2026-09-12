package org.entcore.interoperability.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;
import org.entcore.interoperability.schema.OeipSchemaRegistry;
import org.entcore.interoperability.spi.OeipProviderRegistry;

/**
 * Découverte : ce que cette plateforme sait faire, et les schémas dont elle se sert.
 *
 * Ces routes sont volontairement lisibles par tout compte authentifié : elles ne divulguent
 * aucune donnée, seulement les capacités du format. C'est ce qui permet à une plateforme
 * émettrice de savoir, AVANT d'exporter, ce que la plateforme destinataire saura relire.
 */
public class OeipDiscoveryController extends BaseController {

    private final OeipProviderRegistry registry;
    private final OeipSchemaRegistry schemas;
    private final JsonObject oeipConfig;

    public OeipDiscoveryController(OeipProviderRegistry registry, OeipSchemaRegistry schemas,
                                   JsonObject oeipConfig) {
        this.registry = registry;
        this.schemas = schemas;
        this.oeipConfig = oeipConfig == null ? new JsonObject() : oeipConfig;
    }

    @Get("/capabilities")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void capabilities(HttpServerRequest request) {
        JsonObject levels = new JsonObject()
                .put(OeipFormat.LEVEL_CORE, true)
                .put(OeipFormat.LEVEL_CC, oeipConfig.getJsonObject("cc", new JsonObject())
                        .getBoolean("emit", true))
                .put(OeipFormat.LEVEL_NATIVE, oeipConfig.getBoolean("native", true));

        JsonObject body = new JsonObject()
                .put("format", OeipFormat.FORMAT_ID)
                .put("oeipVersions", new io.vertx.core.json.JsonArray().add(OeipFormat.VERSION))
                .put("mediaType", OeipFormat.MEDIA_TYPE)
                .put("levels", levels)
                .put("sourceSystem", oeipConfig.getString("source-system"))
                .put("nativeFormat", new JsonObject()
                        .put("product", OeipFormat.NATIVE_PRODUCT)
                        .put("archiveVersion", oeipConfig.getString("archive-version")))
                .put("schemaBundleSha256", schemas.getBundleSha256())
                // Construit depuis le registre des mappers réellement enregistrés, JAMAIS depuis
                // la liste des modules déployés : un service déployé sans mapper n'est pas
                // exportable, et doit être refusé plutôt que produire un dossier vide.
                .put("services", registry.capabilitiesJson());

        renderJson(request, body);
    }

    @Get("/schemas")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void schemaIndex(HttpServerRequest request) {
        renderJson(request, schemas.index());
    }

    @Get("/schemas/:version/:name")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void schema(HttpServerRequest request) {
        String version = request.params().get("version");
        String name = request.params().get("name");

        if (!OeipFormat.VERSION.equals(version)) {
            notFound(request, "interoperability.error.version.unsupported");
            return;
        }
        // Le nom vient de l'URL : on ne l'interprète jamais comme un chemin, seule une entrée
        // du lot fermé est servie.
        String raw = schemas.getRawSchema(name);
        if (raw == null) {
            notFound(request);
            return;
        }
        request.response()
                .putHeader("content-type", "application/schema+json; charset=utf-8")
                .end(raw);
    }
}
