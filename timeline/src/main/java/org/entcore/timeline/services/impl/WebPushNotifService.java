package org.entcore.timeline.services.impl;

import fr.wseduc.webutils.Server;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.entcore.common.notification.NotificationUtils;
import org.entcore.common.notification.TimelineNotificationsLoader;
import org.entcore.common.utils.HtmlUtils;
import org.entcore.timeline.services.TimelineConfigService;
import org.entcore.timeline.services.TimelinePushNotifService;

import java.util.ArrayList;
import java.util.List;

/**
 * Envoi des notifications de la timeline par Web Push (navigateurs, application
 * installable), en complément de {@link DefaultPushNotifService} qui, lui, vise
 * les applications mobiles natives via FCM.
 *
 * <h3>Pourquoi ce service ne chiffre rien</h3>
 * Le Web Push impose deux normes : la RFC 8292 (jeton VAPID signé en ES256) et
 * la RFC 8291 (chiffrement de la charge utile — ECDH P-256, HKDF-SHA256,
 * AES128GCM). Aucune des deux n'existe dans entcore. Les implémenter ici
 * signifierait soit écrire de la cryptographie que personne ne relira, soit
 * tirer BouncyCastle et un second client HTTP dans un verticle Vert.x.
 *
 * Ce service se borne donc à ce qu'entcore sait faire mieux que quiconque :
 * décider QUI notifier. Il applique exactement les mêmes filtres que
 * {@link DefaultPushNotifService} — préférence {@code push-notif} par type de
 * notification, restrictions INTERNAL et HIDDEN — puis transmet une liste
 * d'identifiants et un texte déjà traduit au dashboard, qui détient la clé
 * privée VAPID et la bibliothèque de chiffrement.
 *
 * <h3>Différence notable avec le service FCM</h3>
 * {@link DefaultPushNotifService} exige {@code uac.fcmTokens} non vide, car il
 * adresse des appareils. Ici les abonnements vivent dans la base du dashboard :
 * entcore ignore qui en possède un. On transmet donc tous les destinataires
 * éligibles, et le dashboard ne retient que ceux qui sont réellement abonnés.
 *
 * <h3>Configuration</h3>
 * <pre>
 *   push-notif:
 *     - type: webpush
 *       url: http://ent-openent-monolithe-dashboard:3000/dashboard/api/push/send
 *       secret: ${PUSH_INTERNAL_SECRET}
 * </pre>
 */
public class WebPushNotifService extends Renders implements TimelinePushNotifService {

    private static final Logger log = LoggerFactory.getLogger(WebPushNotifService.class);

    /** Au-delà, la notification serait tronquée par le système d'exploitation. */
    private static final int MAX_BODY_LENGTH = 120;

    /** Envoi par paquets : une notification de classe peut viser plusieurs centaines d'élèves. */
    private static final int BATCH_SIZE = 200;

    private final EventBus eb;
    private final HttpClient httpClient;
    private final String sendUrl;
    private final String sharedSecret;
    private TimelineConfigService configService;

    public WebPushNotifService(Vertx vertx, JsonObject config, String sendUrl, String sharedSecret) {
        super(vertx, config);
        this.eb = Server.getEventBus(vertx);
        this.sendUrl = sendUrl;
        this.sharedSecret = sharedSecret;
        // `vertx-core` suffit : un POST avec deux en-têtes ne justifie pas d'ajouter
        // vertx-web-client aux dépendances du module (il n'y figure pas).
        this.httpClient = vertx.createHttpClient(new HttpClientOptions()
                .setConnectTimeout(5000)
                .setIdleTimeout(10));
    }

    public void setConfigService(TimelineConfigService configService) {
        this.configService = configService;
    }

    @Override
    public void sendImmediateNotifs(String notificationName, JsonObject notification,
                                    JsonArray userList, JsonObject notificationProperties) {
        sendUsers(notificationName, notification, userList, notificationProperties);
    }

    @Override
    public void sendNotificationMessageUsers(String notificationName, JsonObject notification,
                                             JsonArray recipientIds, boolean addData) {
        if (configService == null) {
            log.error("[webpush] configService non initialisé.");
            return;
        }
        configService.getNotificationProperties(notificationName, properties -> {
            if (properties.isLeft() || properties.right().getValue() == null) {
                log.error("[webpush] Propriétés introuvables pour la notification " + notificationName);
                return;
            }
            // Mêmes préférences utilisateur que le canal FCM : un usager qui a coupé
            // un type de notification ne doit pas le recevoir par un autre canal.
            NotificationUtils.getUsersPreferences(eb, recipientIds, "tokens: uac.fcmTokens", userList -> {
                if (userList == null) {
                    log.error("[webpush] Préférences utilisateurs introuvables.");
                    return;
                }
                sendUsers(notificationName, notification, userList, properties.right().getValue());
            });
        });
    }

    /*
     * Les envois par « topic » et par « condition » sont des notions propres à FCM
     * (abonnement à un canal côté appareil). Le Web Push n'a pas d'équivalent : un
     * abonnement y désigne toujours un navigateur précis. Ces méthodes sont donc
     * volontairement sans effet plutôt que simulées — feindre un envoi masquerait
     * le fait que ces messages ne parviennent qu'aux applications mobiles.
     */
    @Override
    public void sendNotificationMessageTopic(String notificationName, JsonObject notification,
                                             JsonObject templateParameters, String topic, boolean addData) {
        // sans objet en Web Push
    }

    @Override
    public void sendNotificationMessageCondition(String notificationName, JsonObject notification,
                                                 JsonObject templateParameters, String condition, boolean addData) {
        // sans objet en Web Push
    }

    /*
     * Les « data messages » sont des charges silencieuses, destinées à réveiller une
     * application mobile sans rien afficher. Le Web Push interdit ce comportement :
     * `userVisibleOnly` est obligatoire côté navigateur, et un push qui n'affiche
     * aucune notification fait révoquer l'abonnement. Sans effet, donc.
     */
    @Override
    public void sendDataMessageUsers(String notificationName, JsonObject notification,
                                     JsonObject templateParameters, JsonArray recipientIds) {
        // sans objet en Web Push
    }

    @Override
    public void sendDataMessageTopic(String notificationName, JsonObject notification,
                                     JsonObject templateParameters, String topic) {
        // sans objet en Web Push
    }

    @Override
    public void sendDataMessageCondition(String notificationName, JsonObject notification,
                                         JsonObject templateParameters, String condition) {
        // sans objet en Web Push
    }

    /** Filtre les destinataires éligibles, puis délègue l'envoi au dashboard. */
    private void sendUsers(final String notificationName, final JsonObject notification,
                           final JsonArray userList, final JsonObject notificationProperties) {
        if (userList == null || userList.isEmpty()) return;

        final List<String> userIds = new ArrayList<>();
        for (Object userObj : userList) {
            if (!(userObj instanceof JsonObject)) continue;
            final JsonObject userPref = (JsonObject) userObj;

            final JsonObject preference = userPref
                    .getJsonObject("preferences", new JsonObject())
                    .getJsonObject("config", new JsonObject())
                    .getJsonObject(notificationName, new JsonObject());

            final boolean pushEnabled = preference.getBoolean("push-notif",
                    notificationProperties.getBoolean("push-notif", Boolean.FALSE));
            final String restriction = preference.getString("restriction",
                    notificationProperties.getString("restriction"));

            final boolean restricted =
                    TimelineNotificationsLoader.Restrictions.INTERNAL.name().equals(restriction)
                            || TimelineNotificationsLoader.Restrictions.HIDDEN.name().equals(restriction);

            final String userId = userPref.getString("userId");
            if (pushEnabled && !restricted && userId != null && !userId.isEmpty()) {
                userIds.add(userId);
            }
        }

        if (userIds.isEmpty()) return;

        final JsonObject pushNotif = notification.getJsonObject("pushNotif", new JsonObject());
        final String title = HtmlUtils.unescapeHtmlEntities(pushNotif.getString("title", "ENT"));
        String body = HtmlUtils.unescapeHtmlEntities(pushNotif.getString("body", ""));
        if (body.length() > MAX_BODY_LENGTH) {
            body = body.substring(0, MAX_BODY_LENGTH) + "…";
        }

        // Regroupe les notifications d'une même ressource : cinq messages d'un même
        // fil remplacent la précédente au lieu de s'empiler.
        final String resource = notification.getString("resource");
        final String tag = resource != null ? notificationName + ":" + resource : notificationName;

        final String url = buildUrl(notification);

        for (int i = 0; i < userIds.size(); i += BATCH_SIZE) {
            final JsonArray batch = new JsonArray(
                    new ArrayList<Object>(userIds.subList(i, Math.min(i + BATCH_SIZE, userIds.size()))));
            post(new JsonObject()
                    .put("userIds", batch)
                    .put("title", title)
                    .put("body", body)
                    .put("url", url)
                    .put("tag", tag), notificationName);
        }
    }

    /** Destination ouverte au clic sur la notification. */
    private String buildUrl(JsonObject notification) {
        final JsonObject params = notification.getJsonObject("params", new JsonObject());
        final String resourceUri = params.getString("resourceUri");
        return resourceUri != null && !resourceUri.isEmpty() ? resourceUri : "/";
    }

    private void post(JsonObject payload, String notificationName) {
        httpClient.request(new RequestOptions()
                        .setMethod(HttpMethod.POST)
                        .setAbsoluteURI(sendUrl)
                        .putHeader("Content-Type", "application/json")
                        .putHeader("x-push-secret", sharedSecret))
                .compose(req -> req.send(payload.encode()))
                .onSuccess(response -> {
                    if (response.statusCode() >= 300) {
                        log.error("[webpush] Envoi refusé (" + notificationName + ") : HTTP "
                                + response.statusCode() + " " + response.statusMessage());
                    }
                })
                .onFailure(err ->
                        log.error("[webpush] Envoi impossible (" + notificationName + ")", err));
    }
}
