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

import fr.wseduc.webutils.http.oauth.OAuth2Client;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.notification.NotificationUtils;
import org.entcore.common.notification.ws.OssFcm;

import java.net.URI;

/**
 * Prévient les téléphones d'un compte qu'une connexion attend d'être approuvée.
 *
 * <h3>Un raccourci, jamais une dépendance</h3>
 * <p>L'approbation fonctionne sans cette classe : l'écran web affiche un code que la personne
 * saisit dans son application. La notification ne fait que lui éviter cette saisie quand elle
 * arrive. C'est délibéré — Android peut suspendre le processus, l'instance peut n'avoir aucune
 * configuration de notification, et un facteur d'authentification ne peut pas dépendre d'un
 * service tiers.</p>
 *
 * <h3>Pourquoi ne pas passer par le module timeline</h3>
 * <p>L'envoi habituel ({@code DefaultPushNotifService}) filtre sur la préférence
 * {@code push-notif} du fil d'actualité de chaque personne. Une alerte de sécurité ne doit pas
 * pouvoir être réduite au silence par un réglage d'affichage des nouveautés. {@link OssFcm} et
 * {@link NotificationUtils} vivant dans les bibliothèques communes, le module d'authentification
 * les atteint directement.</p>
 *
 * <p>Le message est de type <i>data</i> : il ne s'affiche pas de lui-même, c'est l'application
 * qui décide quoi en faire — ici, ouvrir l'écran d'approbation.</p>
 */
public class FcmDeviceApprovalPusher {

    private static final Logger log = LoggerFactory.getLogger(FcmDeviceApprovalPusher.class);

    private final OssFcm fcm;

    private FcmDeviceApprovalPusher(final OssFcm fcm) {
        this.fcm = fcm;
    }

    /**
     * Construit l'envoyeur à partir de la configuration du module, ou rend {@code null} si elle
     * est absente. Un {@code null} est un cas normal, pas une panne : l'appelant s'en passe.
     *
     * <p>La configuration lue est la même clé {@code push-notif} que celle du fil d'actualité,
     * et elle est montée de la même façon — même client OAuth2, mêmes champs — pour qu'une
     * instance n'ait rien de nouveau à renseigner.</p>
     */
    public static FcmDeviceApprovalPusher create(final Vertx vertx, final JsonObject config) {
        final JsonObject pushConfig = pushNotifConfig(config);
        if (pushConfig == null) {
            log.info("No push-notif configuration for device approvals: falling back to the displayed code only");
            return null;
        }
        try {
            final OAuth2Client googleOAuth2SSO = new OAuth2Client(
                    URI.create(pushConfig.getString("uri")),
                    null, null, null,
                    pushConfig.getString("tokenUrn"), null, vertx,
                    pushConfig.getInteger("poolSize", 16), true);
            return new FcmDeviceApprovalPusher(new OssFcm(googleOAuth2SSO,
                    pushConfig.getString("client_mail"), pushConfig.getString("scope"),
                    pushConfig.getString("aud"), pushConfig.getString("url"),
                    pushConfig.getString("key"), false, true));
        } catch (Exception e) {
            // Une configuration illisible ne doit pas empêcher le module de démarrer : le
            // parcours par code reste entier.
            log.error("Unable to initialize push notifications for device approvals", e);
            return null;
        }
    }

    /**
     * Accepte aussi bien un objet qu'un tableau de backends, comme la configuration de la
     * timeline, et ne retient que le premier backend FCM rencontré.
     */
    private static JsonObject pushNotifConfig(final JsonObject config) {
        if (config == null) {
            return null;
        }
        final Object raw = config.getValue("push-notif");
        if (raw instanceof JsonObject) {
            return isFcm((JsonObject) raw) ? (JsonObject) raw : null;
        }
        if (raw instanceof JsonArray) {
            for (Object o : (JsonArray) raw) {
                if (o instanceof JsonObject && isFcm((JsonObject) o)) {
                    return (JsonObject) o;
                }
            }
        }
        return null;
    }

    private static boolean isFcm(final JsonObject backend) {
        final String type = backend.getString("type", "fcm");
        return "fcm".equalsIgnoreCase(type);
    }

    /**
     * Envoie à tous les appareils du compte une invitation silencieuse à ouvrir l'approbation.
     *
     * <p>Aucun détail n'est transmis : ni le code, ni les nombres, ni l'origine de la connexion.
     * Une notification traverse les serveurs d'un tiers et peut s'afficher sur un écran
     * verrouillé ; elle ne porte donc que de quoi aller chercher le reste, au chaud, dans
     * l'application.</p>
     */
    public void notifyPendingApproval(final String userId, final String approvalId, final String host) {
        NotificationUtils.getFcmTokensByUser(userId, result -> {
            if (result.isLeft()) {
                log.debug("Unable to read fcm tokens of user " + userId + " : " + result.left().getValue());
                return;
            }
            final JsonArray tokens = result.right().getValue();
            if (tokens == null || tokens.isEmpty()) {
                return;
            }
            for (Object token : tokens) {
                if (!(token instanceof String)) continue;
                final JsonObject message = new JsonObject()
                        .put("token", (String) token)
                        .put("data", new JsonObject()
                                .put("type", "device-approval")
                                .put("approvalId", approvalId)
                                .put("host", host == null ? "" : host));
                try {
                    fcm.sendNotifications(new JsonObject().put("message", message));
                } catch (Exception e) {
                    // Un jeton périmé ou un envoi en échec ne regarde pas l'appelant : la
                    // connexion s'approuve de toute façon avec le code affiché.
                    log.debug("Unable to push device approval notification", e);
                }
            }
        });
    }

}
