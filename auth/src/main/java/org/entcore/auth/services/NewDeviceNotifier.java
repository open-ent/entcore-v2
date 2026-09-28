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

import fr.wseduc.webutils.I18n;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.auth.security.UserAgents;
import org.entcore.common.notification.TimelineHelper;

import java.util.Collections;

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
 * une empreinte de User-Agent, et passe par le fil de nouveautés. Activer les deux
 * enverrait deux alertes pour une même connexion.</p>
 */
public class NewDeviceNotifier {

    private static final Logger log = LoggerFactory.getLogger(NewDeviceNotifier.class);

    /** Nom de la notification, au format {@code <dossier>.<fichier>} sous {@code view-src/notify}. */
    private static final String NOTIFICATION = "new-device.new-device";

    private static final String DEFAULT_DEVICES_URL = "/dashboard/account/devices";

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
        if (notification == null) {
            return;
        }
        final JsonObject infos = clientInfos != null ? clientInfos : new JsonObject();
        final String ip = infos.getString("ip");
        final JsonObject params = new JsonObject()
                .put("deviceLabel", deviceLabel(request, infos.getString("ua")))
                .put("ip", isNotEmpty(ip) ? ip : "")
                .put("devicesUrl", devicesUrl)
                .put("pushNotif", new JsonObject().put("title", "push.notif.new-device"));
        // La ressource porte le deviceId : l'anti-flood du service de notification regroupe
        // ainsi les alertes par appareil et non par utilisateur.
        notification.notifyTimeline(request, NOTIFICATION, null,
                Collections.singletonList(userId), deviceId, params);
        deviceService.markNotified(userId, deviceId)
                .onFailure(e -> log.error("Error marking device " + deviceId + " as notified", e));
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
