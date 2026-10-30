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

package org.entcore.auth.services;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.auth.security.UserAgents;
import org.entcore.common.email.EmailFactory;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.notification.TimelineHelper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Mémorise l'appareil à chaque connexion et prévient l'utilisateur quand elle provient d'un
 * appareil ou d'un réseau inhabituels.
 *
 * <p>Regroupé dans une classe à part parce que les deux chemins de connexion en ont besoin :
 * le formulaire de l'ENT ({@code AuthController}) et les fédérations d'identité
 * ({@code AbstractFederateController} et ses sous-classes SAML / OpenID Connect).</p>
 *
 * <p><u>À ne pas confondre avec</u> {@link org.entcore.auth.users.NewDeviceWarningTask} : celle-ci
 * est une tâche périodique amont qui rejoue le journal {@code events.login_events} en base
 * Postgres, déduit l'appareil du seul User-Agent et prévient par courriel. Elle ne s'active
 * qu'avec la configuration {@code new-device-warning}, absente de nos déploiements.
 * Ici l'alerte est immédiate, s'appuie sur un identifiant d'appareil stable plutôt que sur
 * une empreinte de User-Agent, et passe par le fil de nouveautés doublé d'un courriel.
 * Activer les deux enverrait deux alertes pour une même connexion.</p>
 *
 * <p>Le courriel n'est pas celui que le fil de nouveautés sait envoyer (la notification est
 * {@code INTERNAL}) : sa mise en page commune ne se règle pas depuis le dashboard. Il est rendu
 * ici, avec le gabarit {@code new-device} de la collection Mongo {@code email_templates}, que
 * le super-administrateur modifie dans Configuration ENT → E-mails. Ce même document porte la
 * liste des comptes partagés (comptes de démo distribués aux visiteurs) : pour eux, aucune
 * alerte. Chaque visiteur y arrive d'un appareil neuf, l'alerte n'apprendrait rien à personne
 * et montrerait aux suivants l'adresse IP des précédents.</p>
 */
public class NewDeviceNotifier {

    private static final Logger log = LoggerFactory.getLogger(NewDeviceNotifier.class);

    /** Nom de la notification, au format {@code <dossier>.<fichier>} sous {@code view-src/notify}. */
    private static final String NOTIFICATION = "new-device.new-device";

    private static final String DEFAULT_DEVICES_URL = "/dashboard/account/devices";

    private static final String EMAIL_TEMPLATES_COLLECTION = "email_templates";
    private static final String EMAIL_TEMPLATE_ID = "new-device";
    private static final String EMAIL_VIEW = "email/newDeviceAlert.html";
    /** Logo commun des e-mails, celui du thème cadré par l'annuaire (cf. portal-mui). */
    private static final String COMMON_EMAIL_LOGO_PATH = "/directory/massmail/logo";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRENCH);

    /**
     * Valeurs par défaut du gabarit, tant que le document Mongo n'existe pas ou pour les champs
     * qu'il ne porte pas. Mêmes valeurs que {@code EmailTemplateResource.defaultsFor} côté
     * admin-dashboard, qui les présente à l'édition.
     */
    static JsonObject emailDefaults() {
        return new JsonObject()
                .put("subject", "Nouvelle connexion à votre compte ENT")
                .put("accentColor", "#2160d8")
                .put("brandName", "ENT Scolaire")
                .put("logoUrl", "")
                .put("logoSource", "")
                .put("title", "Nouvelle connexion à votre compte")
                .put("intro", "Votre compte vient d'être utilisé depuis un appareil ou un réseau "
                        + "inhabituel. Si c'est vous, vous n'avez rien à faire. Sinon, déconnectez cet "
                        + "appareil et changez votre mot de passe.")
                .put("ctaLabel", "Voir les appareils connectés")
                .put("signature", "")
                .put("footerNote", "Ce message est généré automatiquement, merci de ne pas y répondre.")
                .put("excludedLogins", String.join("\n",
                        "abd-samad.corbett001", "karim.corbett001", "lilit.upreti001",
                        "blerina.inad001", "emael.shafy001", "oskar.azhan001", "nadia.azhan",
                        "maidy.caputo001", "gianmarco.giudice001"));
    }

    private final DeviceService deviceService;
    private final TimelineHelper notification;
    private final String devicesUrl;

    public NewDeviceNotifier(final DeviceService deviceService, final TimelineHelper notification,
                             final JsonObject config) {
        this.deviceService = deviceService;
        this.notification = notification;
        this.devicesUrl = config != null
                ? config.getString("devices-page-url", DEFAULT_DEVICES_URL) : DEFAULT_DEVICES_URL;
    }

    /**
     * À appeler juste après la création d'une session. N'échoue jamais du point de vue de
     * l'appelant : une connexion réussie ne doit pas être compromise par un problème de
     * journalisation d'appareil.
     *
     * @param clientInfos description de l'appareil, telle que passée à la création de session
     */
    public void onSessionCreated(final String userId, final HttpServerRequest request,
                                 final JsonObject clientInfos) {
        if (userId == null || deviceService == null) {
            return;
        }
        deviceService.recordSighting(userId, clientInfos)
            .onSuccess(sighting -> {
                if (Boolean.TRUE.equals(sighting.getBoolean("shouldNotify"))) {
                    notify(userId, request, clientInfos, sighting.getString("deviceId"));
                }
            })
            .onFailure(e -> log.error("Error recording device sighting for user " + userId, e));
    }

    private void notify(final String userId, final HttpServerRequest request,
                        final JsonObject clientInfos, final String deviceId) {
        CompositeFuture.all(loadEmailTemplate(), loadUser(userId)).onComplete(ar -> {
            final JsonObject tpl = ar.succeeded() ? ar.result().resultAt(0) : emailDefaults();
            final JsonObject user = ar.succeeded() ? ar.result().resultAt(1) : new JsonObject();
            if (isSharedAccount(tpl, user)) {
                return;
            }
            final JsonObject infos = clientInfos != null ? clientInfos : new JsonObject();
            final String ip = isNotEmpty(infos.getString("ip")) ? infos.getString("ip") : "";
            final String label = deviceLabel(request, infos.getString("ua"));
            if (notification != null) {
                final JsonObject params = new JsonObject()
                        .put("deviceLabel", label)
                        .put("ip", ip)
                        .put("devicesUrl", devicesUrl)
                        .put("pushNotif", new JsonObject().put("title", "push.notif.new-device"));
                // La ressource porte le deviceId : l'anti-flood du service de notification regroupe
                // ainsi les alertes par appareil et non par utilisateur.
                notification.notifyTimeline(request, NOTIFICATION, null,
                        Collections.singletonList(userId), deviceId, params);
            }
            sendEmail(request, tpl, user, label, ip);
            deviceService.markNotified(userId, deviceId)
                    .onFailure(e -> log.error("Error marking device " + deviceId + " as notified", e));
        });
    }

    /** Compte listé comme partagé dans le gabarit, par identifiant ou alias, sans tenir compte de la casse. */
    static boolean isSharedAccount(final JsonObject tpl, final JsonObject user) {
        final Set<String> excluded = new HashSet<>();
        for (String login : tpl.getString("excludedLogins", "").split("[\\s,;]+")) {
            if (!login.isEmpty()) {
                excluded.add(login.toLowerCase(Locale.ROOT));
            }
        }
        final String login = user.getString("login");
        final String alias = user.getString("loginAlias");
        return (login != null && excluded.contains(login.toLowerCase(Locale.ROOT)))
                || (alias != null && excluded.contains(alias.toLowerCase(Locale.ROOT)));
    }

    private void sendEmail(final HttpServerRequest request, final JsonObject tpl, final JsonObject user,
                           final String deviceLabel, final String ip) {
        final String to = user.getString("email");
        if (!isNotEmpty(to)) {
            return;
        }
        final EmailSender sender = EmailFactory.getInstance().getSender();
        if (sender == null) {
            return;
        }
        final String baseUrl = Renders.getScheme(request) + "://" + Renders.getHost(request);
        final String source = tpl.getString("logoSource", "");
        final String customLogo = tpl.getString("logoUrl", "");
        final boolean useCustomLogo = isNotEmpty(customLogo) && ("url".equals(source) || source.isEmpty());
        final String footerNote = tpl.getString("footerNote", "");
        final JsonObject params = new JsonObject()
                .put("logoUrl", useCustomLogo ? customLogo : baseUrl + COMMON_EMAIL_LOGO_PATH)
                .put("logoFramed", !useCustomLogo)
                .put("brandName", tpl.getString("brandName"))
                .put("accentColor", tpl.getString("accentColor"))
                .put("title", tpl.getString("title"))
                .put("intro", tpl.getString("intro"))
                .put("ctaLabel", tpl.getString("ctaLabel"))
                .put("signature", tpl.getString("signature", ""))
                .put("footerNote", footerNote)
                .put("hasFooter", isNotEmpty(footerNote))
                .put("displayName", user.getString("displayName", ""))
                .put("deviceLabel", deviceLabel)
                .put("ip", ip)
                .put("date", DATE_FORMAT.format(Instant.now().atZone(ZONE)))
                .put("devicesUrl", devicesUrl.startsWith("http") ? devicesUrl : baseUrl + devicesUrl);
        sender.sendEmail(request, to, null, null, tpl.getString("subject"), EMAIL_VIEW, params, false, ar -> {
            if (ar.failed()) {
                log.error("Error sending new device email to user " + user.getString("id"), ar.cause());
            }
        });
    }

    /** Gabarit {@code email_templates/new-device} fusionné sur les défauts ; les défauts seuls si Mongo ne répond pas. */
    private Future<JsonObject> loadEmailTemplate() {
        final Promise<JsonObject> promise = Promise.promise();
        final JsonObject merged = emailDefaults();
        MongoDb.getInstance().findOne(EMAIL_TEMPLATES_COLLECTION,
                new JsonObject().put("_id", EMAIL_TEMPLATE_ID), res -> {
            final JsonObject stored = "ok".equals(res.body().getString("status"))
                    ? res.body().getJsonObject("result") : null;
            if (stored != null) {
                for (String k : merged.fieldNames().toArray(new String[0])) {
                    final Object v = stored.getValue(k);
                    if (v instanceof String) {
                        merged.put(k, v);
                    }
                }
            }
            promise.complete(merged);
        });
        return promise.future();
    }

    private Future<JsonObject> loadUser(final String userId) {
        final Promise<JsonObject> promise = Promise.promise();
        final String query = "MATCH (u:User {id: $userId}) "
                + "RETURN u.id AS id, u.login AS login, u.loginAlias AS loginAlias, "
                + "u.email AS email, u.displayName AS displayName";
        Neo4j.getInstance().execute(query, new JsonObject().put("userId", userId), res -> {
            final JsonArray rows = "ok".equals(res.body().getString("status"))
                    ? res.body().getJsonArray("result") : null;
            if (rows != null && !rows.isEmpty()) {
                promise.complete(rows.getJsonObject(0));
            } else {
                promise.fail("user.not.found");
            }
        });
        return promise.future();
    }

    private String deviceLabel(final HttpServerRequest request, final String userAgent) {
        final String domain = Renders.getHost(request);
        final String language = I18n.acceptLanguage(request);
        final String label = UserAgents.describe(userAgent,
                I18n.getInstance().translate("auth.device.on", domain, language));
        if (label != null) {
            return label;
        }
        return I18n.getInstance().translate("auth.device.unknown", domain, language);
    }

}
