/* Copyright © "Open Digital Education", 2014
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

package org.entcore.directory.controllers;

import fr.wseduc.bus.BusAddress;
import fr.wseduc.rs.ApiDoc;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.MfaProtected;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.appregistry.ApplicationUtils;
import org.entcore.common.bus.WorkspaceHelper;
import org.entcore.common.http.filter.AdminFilter;
import org.entcore.common.http.filter.AdmlOfStructure;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.Neo4jResult;
import org.entcore.common.notification.NotificationUtils;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.StringUtils;
import org.entcore.directory.pojo.Ent;
import org.entcore.directory.pojo.structure.DefaultAuthModeConfig;
import org.entcore.directory.security.AdminStructureFilter;
import org.entcore.directory.security.AnyAdminOfUser;
import org.entcore.directory.services.StructureBrandingService;
import org.entcore.directory.services.MassMailService;
import org.entcore.directory.services.SchoolService;
import org.vertx.java.core.http.RouteMatcher;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.wseduc.webutils.Utils.handlerToAsyncHandler;
import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.request.RequestUtils.bodyToClass;
import static fr.wseduc.webutils.request.RequestUtils.bodyToJson;
import static org.entcore.common.http.response.DefaultResponseHandler.*;

public class StructureController extends BaseController {

	private MassMailService massMailService;
	private SchoolService structureService;
	private EmailSender notifHelper;
	private final String assetsPath;
	private final JsonObject skins;

	private static final Logger log = LoggerFactory.getLogger(StructureController.class);

	public StructureController(JsonObject skins, String assetsPath) {
		this.skins = skins;
		this.assetsPath = assetsPath;
	}

	/**
	 * Branding de l'établissement (logo, entête, cachet, signature), superposé au thème lors des
	 * publipostages. Peut rester nul : le contrôleur retombe alors sur les seules images du thème,
	 * comme avant l'introduction du service.
	 */
	private StructureBrandingService structureBrandingService;

	/** Lecture des images de branding dans le stockage, pour les PDF (voir brandingForPdf). */
	private WorkspaceHelper workspaceHelper;

	public void setWorkspaceHelper(WorkspaceHelper workspaceHelper) {
		this.workspaceHelper = workspaceHelper;
	}

	public void setStructureBrandingService(StructureBrandingService structureBrandingService) {
		this.structureBrandingService = structureBrandingService;
	}

	/**
	 * Branding de la structure, sous une forme directement exploitable par un gabarit :
	 * les identifiants de documents deviennent des URL absolues (le générateur de PDF est un
	 * service distinct, qui ne saurait pas résoudre un chemin relatif).
	 */
	private io.vertx.core.Future<JsonObject> resolveBranding(final HttpServerRequest request,
			final String structureId) {
		if (structureBrandingService == null || structureId == null) {
			return io.vertx.core.Future.succeededFuture(new JsonObject());
		}
		final String origin = getScheme(request) + "://" + Renders.getHost(request);
		return structureBrandingService.getForStructure(structureId).map(branding -> {
			for (String field : new String[]{"logo", "entete", "cachet", "signature"}) {
				final String documentId = branding.getString(field);
				if (documentId != null) {
					branding.put(field + "Url", origin + "/workspace/document/" + documentId);
				}
			}
			return branding;
		});
	}

	/**
	 * Lecture des images de l'établissement pour le PDF, qui ne doit jamais les télécharger.
	 * <p>
	 * Le générateur de PDF ouvre chaque {@code <img>} avec {@code URL.openStream()}, de façon
	 * bloquante, sur un fil d'événements. Une URL {@code /workspace/document/<id>} est privée
	 * (le générateur n'a pas de session) et part vers l'hôte public, c'est-à-dire vers ce même
	 * serveur : le fil restait bloqué plusieurs minutes et le publipostage n'aboutissait pas.
	 * <p>
	 * Le document est donc lu ici dans le stockage, puis déposé dans un fichier local que le
	 * générateur (même JVM) lit sous une URL {@code file:}. Une donnée intégrée ({@code data:})
	 * serait recopiée dans le gabarit pour chaque compte du lot. Une image illisible est retirée
	 * du branding : le gabarit retombe alors sur l'image du thème.
	 */
	private io.vertx.core.Future<JsonObject> brandingForPdf(final JsonObject branding) {
		final JsonObject out = branding.copy();
		io.vertx.core.Future<Void> chain = io.vertx.core.Future.succeededFuture();
		for (final String field : new String[]{"logo", "entete", "cachet", "signature"}) {
			out.remove(field + "Url");
			final String documentId = branding.getString(field);
			if (documentId == null || workspaceHelper == null) continue;
			chain = chain.compose(v -> localBrandingImage(documentId, "logo".equals(field))
					.map(uri -> {
						out.put(field + "Url", uri);
						return (Void) null;
					})
					.otherwise(err -> {
						log.warn("Branding image " + documentId + " unavailable for PDF: " + err.getMessage());
						return null;
					}));
		}
		return chain.map(v -> out);
	}

	private io.vertx.core.Future<String> localBrandingImage(final String documentId, final boolean asLogo) {
		final io.vertx.core.Promise<String> promise = io.vertx.core.Promise.promise();
		workspaceHelper.readDocument(documentId, document -> {
			if (document == null || document.getData() == null || document.getData().length() == 0) {
				promise.fail("document.not.found");
				return;
			}
			final FileSystem fs = vertx.fileSystem();
			final String dir = System.getProperty("java.io.tmpdir") + File.separator + "openent-branding";
			final String target = dir + File.separator + documentId.replaceAll("[^A-Za-z0-9-]", "_")
					+ (asLogo ? "-logo.png" : "");
			// Écriture puis renommage atomique : un rendu concurrent lit l'ancienne image ou la
			// nouvelle, jamais un fichier à moitié écrit.
			final String part = target + "." + UUID.randomUUID() + ".part";
			final io.vertx.core.Future<Buffer> data = asLogo
					? vertx.executeBlocking(() -> logoInBanner(document.getData()), false)
					: io.vertx.core.Future.succeededFuture(document.getData());
			fs.mkdirs(dir)
					.compose(v -> data)
					.compose(bytes -> fs.writeFile(part, bytes))
					.compose(v -> fs.move(part, target,
							new CopyOptions().setReplaceExisting(true).setAtomicMove(true)))
					.onSuccess(v -> promise.complete(new File(target).toURI().toString()))
					.onFailure(err -> {
						fs.delete(part);
						promise.fail(err);
					});
		});
		return promise.future();
	}

	/** Format du cadre où le logo est posé dans le PDF (largeur / hauteur). */
	private static final double LOGO_BANNER_RATIO = 2.5;
	/** Largeur maximale du cadre, en pixels : au-delà, le PDF s'alourdit sans gain à l'impression. */
	private static final int LOGO_BANNER_MAX_WIDTH = 750;

	/**
	 * Le logo de l'établissement posé, aligné à gauche et centré verticalement, dans un cadre
	 * transparent au format bandeau.
	 * <p>
	 * Les gabarits de publipostage, propres à chaque thème, imposent au logo une <b>largeur</b>
	 * fixe pensée pour le logo horizontal du thème : un logo carré ou en hauteur sortait aussi
	 * haut que large et écrasait la page. Le cadre donne à tout logo la même proportion, donc une
	 * hauteur raisonnable sous la largeur du gabarit, sans toucher aux gabarits des thèmes. Un
	 * format que Java ne sait pas lire (SVG…) est laissé tel quel.
	 */
	private static Buffer logoInBanner(final Buffer data) {
		try {
			final java.awt.image.BufferedImage src =
					javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data.getBytes()));
			if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
				return data;
			}
			final double w = src.getWidth();
			final double h = src.getHeight();
			double frameW = Math.max(w, h * LOGO_BANNER_RATIO);
			double frameH = frameW / LOGO_BANNER_RATIO;
			final double scale = Math.min(1.0, LOGO_BANNER_MAX_WIDTH / frameW);
			frameW *= scale;
			frameH *= scale;
			final int logoW = Math.max(1, (int) Math.round(w * scale));
			final int logoH = Math.max(1, (int) Math.round(h * scale));
			final java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
					(int) Math.round(frameW), (int) Math.round(frameH), java.awt.image.BufferedImage.TYPE_INT_ARGB);
			final java.awt.Graphics2D g = out.createGraphics();
			try {
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
						java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(src, 0, (out.getHeight() - logoH) / 2, logoW, logoH, null);
			} finally {
				g.dispose();
			}
			final java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(out, "png", png);
			return Buffer.buffer(png.toByteArray());
		} catch (Exception e) {
			log.warn("Branding logo kept as is for PDF: " + e.getMessage());
			return data;
		}
	}

	/** Nom de thème acceptable dans un chemin de fichier. */
	private static final java.util.regex.Pattern SKIN_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");
	/** Identifiant de structure (UUID). */
	private static final java.util.regex.Pattern STRUCTURE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{1,64}");

	/**
	 * Logo du thème à poser en tête des e-mails : le logo « texte » (horizontal) quand le thème en
	 * a un, son logo principal sinon. {@code null} si le thème n'en embarque aucun sur ce nœud.
	 */
	private File themeLogoFile(final String skin) {
		if (skin == null || !SKIN_NAME.matcher(skin).matches()) {
			return null;
		}
		for (final String name : new String[]{"logo-text.png", "logo.png"}) {
			final File f = new File(this.assetsPath + "/assets/themes/" + skin + "/img/" + name);
			if (f.isFile()) {
				return f;
			}
		}
		return null;
	}

	/**
	 * Bandeau logo des e-mails de publipostage : le logo de l'établissement quand {@code branding}
	 * en porte un (l'appelant ne transmet un branding que si l'identité de l'établissement est
	 * demandée), celui du thème sinon. L'URL est publique — le destinataire n'a pas de session —
	 * et versionnée par l'image, pour qu'un logo remplacé ne reste pas servi depuis un cache.
	 */
	private JsonObject mailHeader(final HttpServerRequest request, final String skin, final String structureId,
			final JsonObject branding) {
		final String base = getScheme(request) + "://" + Renders.getHost(request) + pathPrefix + "/massmail/logo";
		final String logo = branding == null ? null : branding.getString("logo");
		if (logo != null && structureId != null && STRUCTURE_ID.matcher(structureId).matches()) {
			return new JsonObject()
					.put("url", base + "?structure=" + structureId + "&v=" + logo)
					.put("alt", "");
		}
		final File themeLogo = themeLogoFile(skin);
		if (themeLogo == null) {
			return null;
		}
		return new JsonObject()
				.put("url", base + "?skin=" + skin + "&v=" + themeLogo.lastModified())
				.put("alt", "");
	}

	/**
	 * Logo du bandeau des e-mails de publipostage, au format 2,5:1 (voir {@link #logoInBanner}).
	 * <p>
	 * Route <b>publique</b> : le mail est lu par des destinataires sans session, souvent avant même
	 * d'avoir activé leur compte. Elle ne sert que des logos — celui d'un établissement, que son
	 * site public affiche déjà, ou celui d'un thème, déjà servi sous {@code /assets} — jamais un
	 * autre document du workspace : l'identifiant du document est relu sur la structure.
	 */
	@Get("/massmail/logo")
	@ApiDoc("Public banner logo of mass mail e-mails: the structure's logo (structure=<id>) or the theme's (skin=<skin>).")
	public void massMailLogo(final HttpServerRequest request) {
		final String structureId = request.params().get("structure");
		if (structureId != null) {
			if (!STRUCTURE_ID.matcher(structureId).matches() || workspaceHelper == null) {
				notFound(request);
				return;
			}
			Neo4j.getInstance().execute("MATCH (s:Structure {id: {id}}) RETURN s.brandingLogo as logo",
					new JsonObject().put("id", structureId), Neo4jResult.validUniqueResultHandler(res -> {
						final String documentId = res.isRight() ? res.right().getValue().getString("logo") : null;
						if (documentId == null) {
							notFound(request);
							return;
						}
						workspaceHelper.readDocument(documentId, document -> {
							if (document == null || document.getData() == null || document.getData().length() == 0) {
								notFound(request);
								return;
							}
							sendBannerLogo(request, document.getData());
						});
					}));
			return;
		}
		final String skin = request.params().contains("skin")
				? request.params().get("skin")
				: this.skins.getString(Renders.getHost(request));
		final File themeLogo = themeLogoFile(skin);
		if (themeLogo == null) {
			notFound(request);
			return;
		}
		vertx.fileSystem().readFile(themeLogo.getAbsolutePath()).onComplete(read -> {
			if (read.failed()) {
				notFound(request);
				return;
			}
			sendBannerLogo(request, read.result());
		});
	}

	private void sendBannerLogo(final HttpServerRequest request, final Buffer data) {
		vertx.executeBlocking(() -> logoInBanner(data), false).onComplete(res -> {
			// Une image que Java ne sait pas lire (SVG…) traverse logoInBanner telle quelle : elle
			// n'a pas le format du cadre, et serait déformée par les dimensions fixes du bandeau.
			if (res.failed() || res.result() == data) {
				notFound(request);
				return;
			}
			request.response()
					.putHeader("Content-Type", "image/png")
					// L'URL est versionnée (paramètre v) : un jour de cache ne fige pas un ancien logo.
					.putHeader("Cache-Control", "public, max-age=86400")
					.end(res.result());
		});
	}

	/**
	 * URL de base des images du thème pour le PDF : le dossier local du thème quand il est
	 * présent sur ce nœud (c'est là que sont lus les gabarits), l'hôte public sinon.
	 */
	private String pdfBaseUrl(final HttpServerRequest request, final String host, final String skin) {
		final File imgDir = new File(this.assetsPath + "/assets/themes/" + skin + "/img/");
		if (imgDir.isDirectory()) {
			return imgDir.getAbsoluteFile().toURI().toString();
		}
		return getScheme(request) + "://" + host + "/assets/themes/" + skin + "/img/";
	}

	@Override
	public void init(Vertx vertx, JsonObject config, RouteMatcher rm, Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, config, rm, securedActions);
	}

	@Put("/structure/:structureId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void update(final HttpServerRequest request) {
		bodyToJson(request, pathPrefix + "updateStructure", new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject body) {
				UserUtils.getUserInfos(eb, request, user -> {
					if (user == null || !UserUtils.isSuperAdmin(user)) {
						body.remove("UAI");
						body.remove("hasApp");
						body.remove("ignoreMFA");
					} else if (body.containsKey("UAI") && isEmpty(body.getString("UAI"))) {
						body.putNull("UAI");
					}
					String structureId = request.params().get("structureId");
					structureService.updateAndLog(user, structureId, body, defaultResponseHandler(request));
				});
			}
		});
	}

	@Put("/structure/:structureId/default-auth-config")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	@ResourceFilter(SuperAdminFilter.class)
	public void updateDefaultAuth(final HttpServerRequest request) {
		bodyToClass(request, DefaultAuthModeConfig.class)
				.onSuccess(	body -> UserUtils.getUserInfos(eb, request, user -> {
            String structureId = request.params().get("structureId");
			log.info(String.format("Updating default auth config by %s for structure %s", user.getUserId(), structureId));
            structureService.updateDefaultAuth(user, structureId, body)
					.onSuccess( v -> ok(request))
					.onFailure( th -> new JsonObject().put("error", th.getMessage()));
        })).onFailure(th -> badRequest(request));
	}

	@Get("/structure/:structureId/default-auth-config")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	@MfaProtected()
	public void getDefaultAuth(final HttpServerRequest request) {
		String structureId = request.params().get("structureId");
		structureService.getDefaultAuth(structureId)
				.onSuccess( (defaultAuth) -> renderJson(request, JsonObject.mapFrom(defaultAuth)))
				.onFailure( th -> new JsonObject().put("error", th.getMessage()));
	}

	// D9 (AO ENT ÉCLAT-BFC, personnalisation graphique par Entité/MOA/groupe d'ES) : logo + 2-3
	// couleurs (primaryColor/accentColor) stockés directement comme propriétés du node Structure
	// (pattern déjà en place ailleurs sur ce node, ex. UAI/timetable), écriture directe en Neo4j
	// plutôt que via le event-bus "manual-update-structure" du feeder (qui filtre les champs connus
	// et ignorerait silencieusement des propriétés qu'il ne reconnaît pas). "logo" est un id de
	// document du workspace, réutilisant l'upload générique existant (WorkspaceController#POST
	// /document), sur le même principe que ub.picture/userbook avatar - pas de nouvel upload créé ici.
	//
	// L'identité "papier" de l'établissement suit la même logique et le même stockage : en-tête,
	// cachet, signature du chef d'établissement et son état civil. Ce sont des données d'annuaire,
	// pas de module : un certificat de scolarité, un bulletin ou une attestation les relisent tous
	// au même endroit. Les trois images sont, comme le logo, des ids de document du workspace.
	//
	// Ces propriétés ne viennent pas de l'AAF : aucun import ne les écrase.

	/** Champ du branding -> propriété du node Structure. Ordre stable pour un RETURN lisible. */
	private static final java.util.Map<String, String> BRANDING_FIELDS;
	static {
		final java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
		m.put("primaryColor", "brandingPrimaryColor");
		m.put("accentColor", "brandingAccentColor");
		m.put("logo", "brandingLogo");
		m.put("entete", "brandingEntete");
		m.put("cachet", "brandingCachet");
		m.put("signature", "brandingSignature");
		m.put("cachetPourtour", "brandingCachetPourtour");
		m.put("cachetMention", "brandingCachetMention");
		m.put("signataireCivilite", "brandingSignataireCivilite");
		m.put("signataireNom", "brandingSignataireNom");
		m.put("signatairePrenom", "brandingSignatairePrenom");
		m.put("signataireFonction", "brandingSignataireFonction");
		m.put("signataireLibelle", "brandingSignataireLibelle");
		BRANDING_FIELDS = java.util.Collections.unmodifiableMap(m);
	}

	/** Champs portant un id de document du workspace, donc soumis à {@link #isValidDocumentIdOrEmpty}. */
	private static final java.util.Set<String> BRANDING_DOCUMENT_FIELDS = java.util.Collections
			.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList("logo", "entete", "cachet", "signature")));

	@Put("/structure/:id/branding")
	@ApiDoc("Sets the graphical and documentary branding (logo, header, stamp, signature, signatory) of a structure.")
	@ResourceFilter(AdminStructureFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void setBranding(final HttpServerRequest request) {
		final String structureId = request.params().get("id");
		RequestUtils.bodyToJson(request, body -> {
			// Seuls les champs PRÉSENTS dans le corps sont écrits : deux écrans distincts (thème
			// d'un côté, identité documentaire de l'autre) doivent pouvoir enregistrer chacun sa
			// part sans effacer celle de l'autre. Une clé présente à null efface, ce qui reste le
			// seul moyen de retirer un logo.
			final JsonObject params = new JsonObject().put("id", structureId);
			final StringBuilder sets = new StringBuilder();
			for (java.util.Map.Entry<String, String> champ : BRANDING_FIELDS.entrySet()) {
				final String cle = champ.getKey();
				if (!body.containsKey(cle)) {
					continue;
				}
				final String valeur = body.getString(cle);
				if (cle.endsWith("Color") && !isValidHexColorOrEmpty(valeur)) {
					Renders.badRequest(request, "directory.structure.branding.invalid.color");
					return;
				}
				if (BRANDING_DOCUMENT_FIELDS.contains(cle) && !isValidDocumentIdOrEmpty(valeur)) {
					Renders.badRequest(request, "directory.structure.branding.invalid.document");
					return;
				}
				sets.append(sets.length() == 0 ? "SET " : ", ")
						.append("s.").append(champ.getValue()).append(" = {").append(cle).append("}");
				params.put(cle, StringUtils.isEmpty(valeur) ? null : valeur);
			}
			if (sets.length() == 0) {
				Renders.badRequest(request, "directory.structure.branding.empty");
				return;
			}
			final String query = "MATCH (s:Structure {id: {id}}) " + sets + " RETURN s.id as id, " + brandingReturn();
			Neo4j.getInstance().execute(query, params, Neo4jResult.validUniqueResultHandler(defaultResponseHandler(request)));
		});
	}

	@Get("/structure/:id/branding")
	@ApiDoc("Gets the graphical and documentary branding of a structure.")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void getBranding(final HttpServerRequest request) {
		final String structureId = request.params().get("id");
		// Le nom et la nature de l'établissement accompagnent le branding : ce sont eux qui
		// fournissent les valeurs par défaut du cachet (pourtour = nom, mention déduite de la
		// nature), que l'appelant n'a donc pas à aller chercher ailleurs.
		final String query = "MATCH (s:Structure {id: {id}}) " +
				"RETURN " + brandingReturn() + ", s.name as name, s.type as type, s.city as city";
		final JsonObject params = new JsonObject().put("id", structureId);
		Neo4j.getInstance().execute(query, params, Neo4jResult.validUniqueResultHandler(defaultResponseHandler(request)));
	}

	/** Clause RETURN commune : toutes les propriétés de branding, sous leur nom d'API. */
	private static String brandingReturn() {
		final StringBuilder sb = new StringBuilder();
		for (java.util.Map.Entry<String, String> champ : BRANDING_FIELDS.entrySet()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append("s.").append(champ.getValue()).append(" as ").append(champ.getKey());
		}
		return sb.toString();
	}

	private boolean isValidHexColorOrEmpty(final String color) {
		return StringUtils.isEmpty(color) || color.matches("^#[0-9a-fA-F]{6}$");
	}

	/**
	 * Un id de document du workspace, ou rien. Le contrôle n'a pas pour but d'authentifier le
	 * document — il interdit qu'une chaîne arbitraire (un chemin, une URL externe) se retrouve
	 * stockée là où le rendu des documents attend un identifiant.
	 */
	private boolean isValidDocumentIdOrEmpty(final String documentId) {
		return StringUtils.isEmpty(documentId) || documentId.matches("^[0-9a-zA-Z_-]{16,64}$");
	}

	@Put("/structure/:structureId/link/:userId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void linkUser(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		final String userId = request.params().get("userId");
		structureService.link(structureId, userId, new Handler<Either<String, JsonObject>>() {
			@Override
			public void handle(Either<String, JsonObject> r) {
				if (r.isRight()) {
					if (r.right().getValue() != null) {
						JsonArray a = new JsonArray().add(userId);
						ApplicationUtils.sendModifiedUserGroup(eb, a, handlerToAsyncHandler(new Handler<Message<JsonObject>>() {
							@Override
							public void handle(Message<JsonObject> message) {
								JsonObject j = new JsonObject()
										.put("action", "setDefaultCommunicationRules")
										.put("schoolId", structureId);
								eb.request("wse.communication", j);
							}
						}));
						renderJson(request, r.right().getValue(), 200);
					} else {
						notFound(request);
					}
				} else {
					renderJson(request, new JsonObject().put("error", r.left().getValue()), 400);
				}
			}
		});
	}

	@Delete("/structure/:structureId/unlink/:userId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void unlinkUser(final HttpServerRequest request) {
		final String userId = request.params().get("userId");
		final String structureId = request.params().get("structureId");
		structureService.unlink(structureId, userId, defaultResponseHandler(request));
	}

	@Get("/structure/admin/list")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void listAdmin(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
			@Override
			public void handle(UserInfos user) {
				if (user != null) {
					structureService.listAdmin(user, arrayResponseHandler(request));
				} else {
					unauthorized(request);
				}
			}
		});
	}

	@Put("/structure/:structureId/parent/:parentStructureId")
	@SecuredAction("structure.define.parent")
	public void defineParent(final HttpServerRequest request) {
		final String parentStructureId = request.params().get("parentStructureId");
		final String structureId = request.params().get("structureId");

		// Checking structureId and parentStructureId are different to prevent attaching to same structure
		if (structureId != null && structureId.equals(parentStructureId)) {
			unauthorized(request, "Error: parent structure and child structure are the same structure.");
			return;
		}

		// Checking if structureId is not already a parent of parentStructureId, to avoid loop
		structureService.isParent(parentStructureId, structureId, isParent -> {
			if (isParent.isRight()) {
				if (isParent.right().getValue().booleanValue()) {
					String message = "Error: structure \"" + structureId + "\" is a parent of structure \"" + parentStructureId + "\"";
					unauthorized(request, message);
				} else {
					structureService.defineParent(structureId, parentStructureId, notEmptyResponseHandler(request));
				}
			} else {
				renderError(request, new JsonObject().put("error", isParent.left().getValue()));
			}
		});
	}

	@Delete("/structure/:structureId/parent/:parentStructureId")
	@SecuredAction("structure.remove.parent")
	public void removeParent(final HttpServerRequest request) {
		final String parentStructureId = request.params().get("parentStructureId");
		final String structureId = request.params().get("structureId");
		structureService.removeParent(structureId, parentStructureId, defaultResponseHandler(request));
	}

	@Get("/structure/:structureId/children")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void listChildren(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		structureService.listChildren(structureId, arrayResponseHandler(request));
	}

	@Get("/structures")
	@SecuredAction("structure.list.all")
	public void listStructures(final HttpServerRequest request) {
		String format = request.params().get("format");
		JsonArray fields = new JsonArray().add("id").add("externalId").add("name").add("UAI")
				.add("address").add("zipCode").add("city").add("phone").add("academy");
		if ("XML".equalsIgnoreCase(format)) {
			structureService.list(fields, new JsonArray(), event -> {
                if (event.isRight()) {
                    JsonArray r = event.right().getValue();
                    Ent ent = new Ent();
                    for (Object o: r) {
                        if (!(o instanceof JsonObject)) continue;
                        JsonObject j = (JsonObject) o;
                        Ent.Etablissement etablissement = new Ent.Etablissement();
                        etablissement.setEtablissementId(j.getString("UAI", ""));
                        etablissement.setEtablissementUid(j.getString("UAI", ""));
                        etablissement.setCodePorteur(j.getString("academy", ""));
                        etablissement.setNomCourant(j.getString("name", ""));
                        etablissement.setAdressePlus(j.getString("address", ""));
                        etablissement.setCodePostal(j.getString("zipCode", ""));
                        etablissement.setVille(j.getString("city", ""));
                        etablissement.setTelephone(j.getString("phone", ""));
                        etablissement.setFax("");
                        ent.getEtablissement().add(etablissement);
                    }
                    try {
                        StringWriter response = new StringWriter();
                        JAXBContext context = JAXBContext.newInstance(Ent.class);
                        Marshaller marshaller = context.createMarshaller();
                        marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
                        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
                        marshaller.marshal(ent, response);
                        request.response().putHeader("content-type", "application/xml");
                        request.response().end(response.toString());
                    } catch (JAXBException e) {
                        log.error(e.toString(), e);
                        request.response().setStatusCode(500);
                        request.response().end(e.getMessage());
                    }
                } else {
                    leftToResponse(request, event.left());
                }
            });
		} else {
			structureService.list(fields,new JsonArray(), arrayResponseHandler(request));
		}
	}

	/**
	 * Endpoint to retrieve the levels of the users of a structure,
	 * and of its sub-structures if query param "inherit" is true.
	 * @param request the http request
	 */
	@Get("/structure/:structureId/levels")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getLevels(final HttpServerRequest request) {
		final boolean inherit = "true".equalsIgnoreCase(request.params().get("inherit"));
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
			@Override
			public void handle(UserInfos infos) {
				structureService.getLevels(request.params().get("structureId"), inherit, infos, arrayResponseHandler(request));
			}
		});
	}

	@Put("/structure/:structureId/levels-of-education")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void setLevels(final HttpServerRequest request) {
		bodyToJson(request, new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject body) {
				JsonArray jsonLevelsOfEducation = body.getJsonArray("levelsOfEducation");
				List<Integer> levelsOfEducation = jsonLevelsOfEducation.getList();
				structureService.setLevelsOfEducation(
						request.params().get("structureId"),
						levelsOfEducation,
						defaultResponseHandler(request)
				);
			}
		});
	}

	@Put("/structure/:structureId/distributions")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void setDistributions(final HttpServerRequest request) {
		bodyToJson(request, new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject body) {
				JsonArray jsonDistributions = body.getJsonArray("distributions");
				List<String> distributions = jsonDistributions.getList();
				structureService.setDistributions(
						request.params().get("structureId"),
						distributions,
						defaultResponseHandler(request)
				);
			}
		});
	}

	@Get("/structure/:structureId/massMail/users")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getMassmailUsers(final HttpServerRequest request){
		UserUtils.getUserInfos(eb, request, infos -> {
            final JsonObject filter = new JsonObject();
            final String structureId = request.params().get("structureId");
            final List<String> sorts = request.params().getAll("s");
            final Boolean filterMail = request.params().contains("mail") ?
                    new Boolean(request.params().get("mail")) :
                    null;

            filter
                .put("profiles", new JsonArray(request.params().getAll("p")))
                .put("levels", new JsonArray(request.params().getAll("l")))
                .put("classes", new JsonArray(request.params().getAll("c")))
                .put("sort", new JsonArray(sorts));

            if(request.params().contains("a")){
                filter.put("activated", request.params().get("a"));
            }

            if(request.params().contains("dateFilter") && request.params().contains("date")) {
                filter.put("dateFilter", request.params().get("dateFilter"));
                filter.put("date", request.params().get("date"));
}

            massMailService.massmailUsers(structureId, filter, filterMail, true, infos, arrayResponseHandler(request));
        });
	}

	@Get("/structure/:structureId/massMail/allUsers")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getMassMailUsersList(final HttpServerRequest request){
		UserUtils.getUserInfos(eb, request, infos -> {
            final String structureId = request.params().get("structureId");
            massMailService.massMailAllUsersByStructure(structureId, infos, arrayResponseHandler(request));
        });
	}

	@Get("/structure/massmessaging/template")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void getMassMessageTemplate(final HttpServerRequest request) {
		FileSystem fs = vertx.fileSystem();

		getSkin(request, res -> {

			final String skin;
			if (res.isLeft() || res.right().getValue() == null) {
				skin = this.skins.getString(Renders.getHost(request));
			} else {
				skin = res.right().getValue();
			}

			final String assetsPath = this.assetsPath + "/assets/themes/" + skin;
			final String templatePath = assetsPath + "/template/directory/massmessage_asm_default.html";

			fs.readFile(templatePath, result -> {

				if (result.succeeded()) {
					String html = result.result().toString(StandardCharsets.UTF_8);

					request.response()
							.setStatusCode(200)
							.putHeader("content-type", "text/html; charset=utf-8")
							.end(html);
				} else {
					request.response()
							.setStatusCode(500)
							.putHeader("content-type", "text/plain; charset=utf-8")
							.end("Failed to load template");
				}

			});
		});
	}

	@Get("/structure/massMail/:userId/:type")
	@ResourceFilter(AnyAdminOfUser.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void performMassmailUser(final HttpServerRequest request){
		final String userId = request.params().get("userId");
		final String type = request.params().get("type");
		final String filename = "mettre le nom de de l'utilisateur";
		if(userId == null){
			badRequest(request);
			return;
		}

		getSkin(request, result -> {

			final String skin;
			if (result.isLeft() || result.right().getValue() == null) {
				skin = this.skins.getString(Renders.getHost(request));
			} else {
				skin = result.right().getValue();
			}

			final String assetsPath = this.assetsPath + "/assets/themes/" + skin;
			final String templatePath = assetsPath + "/template/directory/";
			final String baseUrl = pdfBaseUrl(request, Renders.getHost(request), skin);

			UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
				public void handle(final UserInfos infos) {

					//PDF
					if("pdf".equals(type)){
						massMailService.massMailUser(userId, infos, new Handler<Either<String,JsonArray>>() {
							public void handle(Either<String, JsonArray> result) {
								if(result.isLeft()){
									forbidden(request);
									return;
								}

								massMailService.massMailTypePdf(infos, request, templatePath, baseUrl, filename, "pdf", result.right().getValue());

							}
						});
					}
					//Mail
					else if("mail".equals(type)){
						massMailService.massMailUser(userId, infos, new Handler<Either<String,JsonArray>>() {
							public void handle(final Either<String, JsonArray> result) {
								if(result.isLeft()){
									forbidden(request);
									return;
								}

								massMailService.massMailTypeMail(infos, request, templatePath, result.right().getValue(), null,
										mailHeader(request, skin, null, null));
							}
						});
					} else {
						badRequest(request);
					}

				}
			});
		});
	}

	@Get("/structure/:structureId/massMail/process/:type")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void performMassmail(final HttpServerRequest request){
		final String structureId = request.params().get("structureId");
		final String type = request.params().get("type");
		final JsonObject filter = new JsonObject();
		final String filename = request.params().get("filename");
		final Boolean filterMail = request.params().contains("mail") ?
				new Boolean(request.params().get("mail")) :
				null;

		filter
			.put("profiles", new JsonArray(request.params().getAll("p")))
			.put("levels", new JsonArray(request.params().getAll("l")))
			.put("classes", new JsonArray(request.params().getAll("c")))
			.put("sort", new JsonArray(request.params().getAll("s")));

		if(request.params().contains("a")){
			filter.put("activated", request.params().get("a"));
		}

		if (request.params().contains("includeFederated") && Boolean.parseBoolean(request.params().get("includeFederated"))) {
			filter.put("includeFederated", true);
		}

        if(request.params().contains("adml")){
            filter.put("adml", request.params().get("adml"));
        }

        if(request.params().contains("dateFilter") && request.params().contains("date")) {
            filter.put("dateFilter", request.params().get("dateFilter"));
            filter.put("date", request.params().get("date"));
        }

		getSkin(request, result -> {
            // If query parameter b does not exist, then do nothing.
            if(request.params().contains("b")){
                // Keep unblocked users iif b has an explicit value of "false".
                // Keep blocked users in any other case.
                final boolean keepUnblockedUsers = "false".equalsIgnoreCase(request.params().get("b"));
                filter.put("blocked", !keepUnblockedUsers);
            }
			final String skin;
			if (result.isLeft() || result.right().getValue() == null) {
				skin = this.skins.getString(Renders.getHost(request));
			} else {
				skin = result.right().getValue();
			}

			final String assetsPath = this.assetsPath + "/assets/themes/" + skin;
			final String templatePath = assetsPath + "/template/directory/";
			final String baseUrl = pdfBaseUrl(request, Renders.getHost(request), skin);

			// Le branding de l'établissement est résolu une fois pour tout le lot : c'est lui qui
			// met le logo et le cachet de l'établissement sur les courriers d'identifiants, là où
			// seules les images du thème (communes à tout le domaine) apparaissaient jusqu'ici.
			// `branding=false` le laisse de côté : l'administrateur choisit alors explicitement le
			// courrier aux couleurs de l'ENT, même pour un établissement doté d'une identité.
			final boolean withBranding = !"false".equalsIgnoreCase(request.params().get("branding"));
			(withBranding ? resolveBranding(request, structureId)
					: io.vertx.core.Future.succeededFuture(new JsonObject())).onComplete(brandingRes -> {
				final JsonObject branding = brandingRes.succeeded() ? brandingRes.result() : new JsonObject();
				UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
					public void handle(final UserInfos infos) {

						//PDF
						if("pdf".equals(type) || "newPdf".equals(type) || "simplePdf".equals(type)){
							massMailService.massmailUsers(structureId, filter, filterMail, true, infos, new Handler<Either<String,JsonArray>>() {
								public void handle(Either<String, JsonArray> result) {
									if(result.isLeft()){
										forbidden(request);
										return;
									}

									brandingForPdf(branding).onComplete(pdfBranding -> massMailService.massMailTypePdf(infos, request, templatePath, baseUrl, filename, type, result.right().getValue(), pdfBranding.succeeded() ? pdfBranding.result() : new JsonObject()));
								}
							});
						}
						//Mail
						else if("mail".equals(type)){
							massMailService.massmailUsers(structureId, filter, filterMail, true, infos, new Handler<Either<String,JsonArray>>() {
								public void handle(final Either<String, JsonArray> result) {
									if(result.isLeft()){
										forbidden(request);
										return;
									}

									massMailService.massMailTypeMail(infos, request, templatePath, result.right().getValue(), branding,
											mailHeader(request, skin, structureId, branding), structureId);
								}
							});
						} else {
							badRequest(request);
						}

					}
				});
			});
		});
	}

	/**
	 * Avancement puis bilan d'un publipostage par courriel lancé par {@code /massMail/process/mail}
	 * (identifiant {@code jobId} de sa réponse). Seul le compte qui l'a lancé, ou un
	 * super-administrateur, peut le lire ; le bilan est gardé 24 h après la fin de l'envoi.
	 */
	@Get("/massmail/report/:jobId")
	@SecuredAction(value = "", type = ActionType.AUTHENTICATED)
	public void massMailReport(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, infos -> {
			final JsonObject report = massMailService.massMailReport(request.params().get("jobId"), infos);
			if (report == null) {
				notFound(request);
			} else {
				renderJson(request, report);
			}
		});
	}

	@Post("/class-admin/massmail")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void classAdminMassMail(final HttpServerRequest request){
		bodyToJson(request, new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject body) {

				JsonArray userIds = body.getJsonArray("ids");
				String type = body.getString("type");
				String schoolId = body.getString("structureId");

				if (userIds == null || userIds.isEmpty() || type == null || schoolId == null) {
					badRequest(request);
				}

				final String host = Renders.getHost(request);

				getSkin(request, result -> {

					final String skin;
					if (result.isLeft() || result.right().getValue() == null) {
						skin = skins.getString(host);
					} else {
						skin = result.right().getValue();
					}

					final String assetsPath = StructureController.this.assetsPath +
							"/assets/themes/" + skin;
					final String templatePath = assetsPath + "/template/directory/";
					final String baseUrl = pdfBaseUrl(request, host, skin);

					UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
						public void handle(final UserInfos infos) {

							JsonObject filterObj = new JsonObject();
							filterObj.put("userIds",userIds);
							// We set neither true or false to get both activated and non-activated users
							filterObj.put("activated","both");
							filterObj.put("sort",new JsonArray().add("displayName"));

							// Même branding que le publipostage par structure : schoolId EST la structure
							// concernée, un courrier de classe doit porter l'en-tête de son établissement.
							resolveBranding(request, schoolId).onComplete(brandingRes -> {
							final JsonObject branding = brandingRes.succeeded() ? brandingRes.result() : new JsonObject();
							massMailService.massmailNoCheck(schoolId, filterObj, infos, new Handler<Either<String, JsonArray>>() {
								@Override
								public void handle(Either<String, JsonArray> result) {
									if (result.isLeft()) {
										forbidden(request);
										return;
									}
									JsonArray users = result.right().getValue();
									switch (type) {
										case "pdf":
										case "newPdf":
										case "simplePdf":
											brandingForPdf(branding).onComplete(pdfBranding -> massMailService.massMailTypePdf(infos, request, templatePath, baseUrl, "massmail", type, users, pdfBranding.succeeded() ? pdfBranding.result() : new JsonObject()));
											break;
										case "mail":
											massMailService.massMailTypeMail(infos, request, templatePath, users, branding,
													mailHeader(request, skin, schoolId, branding), schoolId);
											break;
										case "csv":
											massMailService.massMailTypeCSV(request, users);
											break;
										default:
											badRequest(request);
									}
								}
							});
							});
						}
					});

				});

			}
		});
	}

	@Get("/structure/:structureId/metrics")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(AdmlOfStructure.class)
	@MfaProtected()
	public void metrics(final HttpServerRequest request){
		structureService.getMetrics(request.params().get("structureId"), defaultResponseHandler(request));
	}
	
	@Get("/structure/:id/sources")
	@ResourceFilter(AdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void listSources(final HttpServerRequest request) {
		String structureId = request.params().get("id");
		this.structureService.listSources(structureId, arrayResponseHandler(request));
	}

	@Get("/structure/:id/aaffunctions")
	@ResourceFilter(AdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@MfaProtected()
	public void listAafFunctions(final HttpServerRequest request) {
		String structureId = request.params().get("id");
		this.structureService.listAafFunctions(structureId, arrayResponseHandler(request));
	}

	@Get("/structure/:id/quicksearch/users")
	@SecuredAction(type = ActionType.RESOURCE, value = "")
	@ResourceFilter(AdminStructureFilter.class)
	@MfaProtected()
	public void quickSearchUsers(HttpServerRequest request) {
		String structureId = request.params().get("id");
		String input = request.params().get("input");

		if(input == null || input.trim().length() == 0){
			badRequest(request);
			return;
		}

		this.structureService.quickSearchUsers(structureId, input, arrayResponseHandler(request));
	}

	@Get("/structure/:id/users")
	@SecuredAction(type = ActionType.RESOURCE, value = "")
	@ResourceFilter(AdminStructureFilter.class)
	@MfaProtected()
	public void userList(HttpServerRequest request) {
		String structureId = request.params().get("id");
		UserUtils.getUserInfos(eb, request, user -> {
			if (user != null) {
				final boolean isAdmc = (user.getFunctions() != null && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN));
				this.structureService.userList(structureId, false, isAdmc, arrayResponseHandler(request));
			} else {
				unauthorized(request, "invalid.user");
			}
		});
	}

	@Get("/structure/:id/removedUsers")
	@SecuredAction(type = ActionType.RESOURCE, value = "")
	@ResourceFilter(AdminStructureFilter.class)
	@MfaProtected()
	public void removedUserList(HttpServerRequest request) {
		String structureId = request.params().get("id");
		UserUtils.getUserInfos(eb, request, user -> {
			if (user != null) {
				final boolean isAdmc = (user.getFunctions() != null && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN));
				this.structureService.userList(structureId, true, isAdmc, arrayResponseHandler(request));
			} else {
				unauthorized(request, "invalid.user");
			}
		});
	}

	@Put("structure/:id/profile/block")
	@SecuredAction( value = "", type = ActionType.RESOURCE)
	@ResourceFilter(AdminStructureFilter.class)
	@MfaProtected()
	public void blockUsers(final HttpServerRequest request) {
		RequestUtils.bodyToJson(request, new Handler<JsonObject>() {
			@Override
			public void handle(JsonObject json) {
				final String structureId = request.params().get("id");
				final String profile = json.getString("profile");
				final boolean block = json.getBoolean("block", true);
				UserUtils.getUserInfos(eb, request, user -> {
					if (user != null) {
						final boolean isAdmc = (user.getFunctions() != null && user.getFunctions().containsKey(DefaultFunctions.SUPER_ADMIN));
						structureService.blockUsers(structureId, profile, block, isAdmc, new Handler<JsonObject>() {
							@Override
							public void handle(JsonObject r) {
								if ("ok".equals(r.getString("status"))) {
									request.response().setStatusCode(204);
									request.response().end();

									// Create a Trace
									JsonObject traceData = new JsonObject()
											.put("action", block ? "BLOCK" : "UNBLOCK")
											.put("profile", profile)
											.put("structureId", structureId)
											.put("userId", user.getUserId())
											.put("userName", user.getUsername());
									eb.request("wse.admin.block.trace", traceData, response -> {
										if (response.succeeded()) {
											log.info("Trace created successfully: " + traceData.toString());
										} else {
											log.error("An error occurred during the trace creation", response.cause());
										}
									});

									JsonArray usersId = r.getJsonArray("result").getJsonObject(0).getJsonArray("usersId");
									eb.publish("auth.store.lock.event", new JsonObject().put("ids", usersId).put("block", block));
									if (block) {
										NotificationUtils.deleteFcmTokens(usersId, ar -> {
											if (ar.isLeft()) {
												log.error("Failed to delete FCM tokens when block structure : " + structureId + " " + ar.left().getValue());
											}
										});
									}
									for (Object userId : usersId) {
										UserUtils.deletePermanentSession(eb, (String) userId, null, null, false, new Handler<Boolean>() {
											@Override
											public void handle(Boolean event) {
												if (!event) {
													log.error("Error delete permanent session with userId : " + userId);
												}
											}
										});
										UserUtils.deleteCacheSession(eb, (String) userId, null, new Handler<JsonArray>() {
											@Override
											public void handle(JsonArray event) {
												if (event == null) {
													log.error("Error delete cache session with userId : " + userId);
												}
											}
										});
									}
								}
								else {
									badRequest(request);
								}
							}
						});
					} else {
						unauthorized(request, "invalid.user");
					}
				});
			}
		});
	}

	@Put("/structure/:structureId/resetName")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(AdmlOfStructure.class)
	@MfaProtected()
	public void resetStructureName(HttpServerRequest request)
	{
		structureService.resetName(request.params().get("structureId"), defaultResponseHandler(request));
	}

	public void setMassMailService(MassMailService massMailService) {
		this.massMailService = massMailService;
	}

	public void setStructureService(SchoolService structureService) {
		this.structureService = structureService;
	}

	public void setNotifHelper(EmailSender notifHelper) {
		this.notifHelper = notifHelper;
	}

	private void getSkin(HttpServerRequest request, Handler<Either<String,String>> handler) {
		eb.request("userbook.preferences", new JsonObject().put("action", "get.currentuser")
				.put("request", new JsonObject().put("headers", new JsonObject().put("Cookie", request.getHeader("Cookie"))))
				.put("application", "theme"), result -> {
			if (result.succeeded()) {
				JsonObject data = (JsonObject)(result.result().body());
				String skin = null;
				try {
					skin = data.getJsonObject("value").getString("preference");
				} catch (Exception e) {
					skin = null;
				} finally {
					handler.handle(new Either.Right<>(skin));
				}
			} else {
				handler.handle(new Either.Left<>(result.cause().getMessage()));
			}
		});
	}

	@Put("/structure/check/uai")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void checkUAIs(final HttpServerRequest request) {
		bodyToJson(request, body -> {
			JsonArray uais = body.getJsonArray("list");
			structureService.getStructureNameByUAI(uais, handler -> {
				if (handler.isLeft()) {
					renderError(request, new JsonObject().put("error", handler.left().getValue()));
				} else {
					renderJson(request, handler.right().getValue());
				}
			});
		});
	}

	@Put("/structure/:structureId/duplicate")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void duplicate(final HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		bodyToJson(request, body -> {
			JsonArray targetUAIs = body.getJsonArray("list");
			JsonObject options = body.getJsonObject("options");
			JsonObject infos = body.getJsonObject("infos");
			structureService.duplicateStructureSettings(structureId, targetUAIs, options, handler -> {
				final JsonObject res = handler.isRight() ? handler.right().getValue() : new JsonObject();
				UserUtils.getUserInfos(eb, request, user -> {
					eb.request("directory", new JsonObject().put("action", "getUser").put("userId", user.getUserId()),
							handlerToAsyncHandler(event -> {
								if (!"ok".equals(event.body().getString("status"))) {
									log.error("[StructureController] Error getting user: " + event.body().getString("message"));
								} else {
									final String email = event.body().getJsonObject("result").getString("email");
									if (StringUtils.isEmpty(email)) {
										res.put("email", false);
									} else {
										JsonObject params = new JsonObject()
												.put("username", user.getUsername())
												.put("structurename", infos.getString("structure"))
												.put("targets", infos.getJsonArray("targets"));
										if (handler.isLeft()) {
											params.put("error", handler.left().getValue());
										}
										processTemplate(request, "email/duplicate.html", params, template -> {
											Handler<AsyncResult<Message<JsonObject>>> asyncHandler = handlerToAsyncHandler(event2 -> {
												if (!"ok".equals(event2.body().getString("status"))) {
													log.error("[StructureController] Error reading template: " + event2.body().getString("message"));
												}
											});
											notifHelper.sendEmail(request, email, null, null, "admin.duplicate.notify.subject",
													template, null, true, asyncHandler);
										});
										res.put("email", true);
									}
									if (handler.isRight()) {
										renderJson(request, res);
									} else {
										log.error("[StructureController] Error duplicating structure setting: " + handler.left().getValue());
										renderError(request, new JsonObject().put("error", handler.left().getValue()));
									}
								}
							}));
				});
			});
		});
	}

	@Put("/structure/check/gar")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void checkGAR(final HttpServerRequest request) {
		bodyToJson(request, body -> {
			JsonArray uais = body.getJsonArray("uais");
			structureService.checkGAR(uais, handler -> {
				if (handler.isLeft()) {
					renderError(request, new JsonObject().put("error", handler.left().getValue()));
				} else {
					renderJson(request, handler.right().getValue());
				}
			});
		});
	}

	@Put("/structure/gar/activate")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void activateGar(final HttpServerRequest request) {
		bodyToJson(request, body -> {
			JsonArray targetUAIs = body.getJsonArray("uais", new JsonArray());
			String garId = body.getString("garId");

			if (StringUtils.isEmpty(garId) || targetUAIs.isEmpty()) {
				badRequest(request);
				return;
			}

			this.structureService.activateGar(garId, targetUAIs, config.getString("gar-group-name", "RESP-AFFECT-GAR"),
				config.getString("gar-app-name", "GAR_AFFECTATION_IHM_CONNECTEUR"), config.getString("gar-role-name",
							"Mediacentre - Accès"), config.getJsonObject("gar-ra-assignment-policy"), result -> {
					if (result.isRight()) {
						renderJson(request, result.right().getValue());
					} else {
						renderError(request);
					}
				});
		});
	}

	@Get("/structure/:id/contacts")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	@MfaProtected()
	public void listStructureContacts(HttpServerRequest request) {
		final String structureId = request.params().get("id");
		structureService.listContacts(structureId).onComplete(res->{
			if (res.succeeded()) {
				Renders.renderJson(request, res.result());
			} else {
				Renders.renderError(request, new JsonObject().put("error", "list.structure.contacts.cantload"));
				log.error("Failed to load contacts of structure : " + structureId, res.cause());
			}
		});
	}

	@BusAddress("directory.structure.quiethours.preferences")
	public void structureQuietHoursPreferences(final Message<JsonObject> message) {
		final String action = message.body().getString("action", "");
		final String structureId = message.body().getString("structureId");

		switch (action) {
			case "get":
				structureService.getQuietHoursPreferences(structureId)
						.onSuccess(prefs -> message.reply(new JsonObject()
								.put("status", "ok")
								.put("message", toQuietHoursPreferencesResponse(prefs, structureId))))
						.onFailure(error -> message.reply(new JsonObject()
								.put("status", "error")
								.put("message", error.getMessage())));
				break;
			case "set":
				final JsonObject preferences = message.body().getJsonObject("preferences", new JsonObject());
				structureService.setQuietHoursPreferences(structureId, preferences)
						.onSuccess(prefs -> message.reply(new JsonObject()
								.put("status", "ok")
								.put("message", toQuietHoursPreferencesResponse(prefs, structureId))))
						.onFailure(error -> message.reply(new JsonObject()
								.put("status", "error")
								.put("message", error.getMessage())));
				break;
			default:
				message.reply(new JsonObject()
						.put("status", "error")
						.put("message", "Invalid action: " + action));
		}
	}

	private JsonObject toQuietHoursPreferencesResponse(JsonObject data, String structureId) {
		final JsonObject output = new JsonObject();

		if (data == null) {
			return output;
		}

		final String timezoneStr = data.getString("notificationTimezone");
		if (timezoneStr != null) {
			try {
				final JsonObject parsedTimezone = new JsonObject(timezoneStr);
				final String timezone = parsedTimezone.getString("timezone");
				if (timezone != null) {
					output.put("timezone", timezone);
				}
			} catch (Exception decodeException) {
				log.error("Failed to decode timezone preference for structure " + structureId, decodeException);
			}
		}

		final String quietHoursStr = data.getString("notificationQuietHours");
		if (quietHoursStr != null) {
			try {
				final JsonObject quietHours = new JsonObject(quietHoursStr);
				quietHours.remove("managedBy");
				output.put("quietHours", quietHours);
			} catch (Exception decodeException) {
				log.error("Failed to decode quietHours preference for structure " + structureId, decodeException);
			}
		}

		return output;
	}

}
