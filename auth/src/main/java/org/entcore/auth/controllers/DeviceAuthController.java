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

package org.entcore.auth.controllers;

import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.http.Renders;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.security.DeviceAssertion;
import org.entcore.auth.security.DeviceChallengeStore;
import org.entcore.auth.security.DevicePublicKeys;
import org.entcore.auth.services.DeviceCredentialService;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.Mfa;
import org.entcore.common.datavalidation.UserValidation;

import java.security.PublicKey;
import java.util.UUID;
import java.util.function.BiConsumer;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Enrôlement et gestion des clés d'appareil.
 *
 * <p>Permet d'authentifier le téléphone d'une personne <b>sans connaître son numéro</b> : au
 * lieu d'envoyer un code sur une ligne qu'il faut d'abord collecter, on vérifie une signature
 * produite par une clé privée que le téléphone garde dans son coffre matériel et que l'OS ne
 * laisse utiliser qu'après déverrouillage biométrique.</p>
 *
 * <p>Ces routes ne changent rien à la connexion : elles enregistrent une clé, la listent et la
 * révoquent. L'usage de la clé pour ouvrir une session ou valider un second facteur fait
 * l'objet des étapes suivantes.</p>
 *
 * <p><b>Déploiement</b> : ces routes portent {@code @SecuredAction}, dont les métadonnées sont
 * écrites au déploiement du module. Une mise à jour à chaud du seul code les laisserait en 404.</p>
 */
public class DeviceAuthController extends BaseController {

    /** Usage porté par les assertions d'enrôlement. */
    private static final String PURPOSE_ENROL = "enrol";

    /** Longueur maximale d'un nom d'appareil affichable, saisi par l'utilisateur. */
    private static final int DEVICE_NAME_MAX = 64;

    private DeviceCredentialService credentialService;
    private DeviceChallengeStore challengeStore;

    /**
     * Ouvre un enrôlement : le serveur désigne l'appareil et remet un défi que le téléphone
     * devra signer avec la clé qu'il vient de créer.
     *
     * <p>Corps accepté : {@code {deviceId?, deviceName?}}. {@code deviceId} permet au téléphone
     * de se rattacher à un appareil déjà connu du compte quand il réenrôle (changement de
     * biométrie, réinstallation) ; il ne vaut aucune preuve d'identité et reste toujours
     * contraint au compte de la session.</p>
     */
    @Post("/device/enrolment/start")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void startEnrolment(final HttpServerRequest request) {
        withSession(request, (session, userInfos) -> RequestUtils.bodyToJson(request, body -> {
            final String deviceId = acceptableDeviceId(body.getString("deviceId"));
            final boolean proven = isSessionProven(session, userInfos);
            challengeStore.issue(PURPOSE_ENROL, userInfos.getUserId(), null)
                    .onSuccess(challenge -> renderJson(request, new JsonObject()
                            .put("challenge", challenge)
                            .put("deviceId", deviceId)
                            .put("audience", audienceOf(request))
                            .put("purpose", PURPOSE_ENROL)
                            // Annoncé pour que l'application puisse le dire à l'utilisateur ;
                            // seule la valeur recalculée à la confirmation fait foi.
                            .put("assurance", proven
                                    ? DeviceCredentialService.ASSURANCE_PROVEN
                                    : DeviceCredentialService.ASSURANCE_SELF)))
                    .onFailure(e -> {
                        log.error("Error starting device enrolment for " + userInfos.getUserId(), e);
                        renderError(request);
                    });
        }));
    }

    /**
     * Confirme l'enrôlement : la clé publique est enregistrée si, et seulement si, l'assertion
     * qui l'accompagne a bien été produite par la clé privée correspondante.
     *
     * <p>Corps attendu : {@code {publicKey, alg, assertion, deviceId, deviceName?, platform?}}.</p>
     *
     * <p>La clé est enregistrée avec le niveau d'assurance que la session permet d'établir :
     * {@code proven} si le second facteur a déjà été franchi, {@code self} sinon. Une clé
     * {@code self} pourra ouvrir une session, jamais tenir lieu de second facteur — sans cette
     * distinction, un mot de passe dérobé suffirait à fabriquer un deuxième facteur.</p>
     */
    @Post("/device/enrolment/confirm")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void confirmEnrolment(final HttpServerRequest request) {
        withSession(request, (session, userInfos) -> RequestUtils.bodyToJson(request, body -> {
            final String alg = body.getString("alg");
            final String assertion = body.getString("assertion");
            final String deviceId = acceptableDeviceId(body.getString("deviceId"));
            if (isEmpty(assertion)) {
                badRequest(request, "device.assertion.missing");
                return;
            }

            final PublicKey publicKey;
            final String credentialId;
            try {
                publicKey = DevicePublicKeys.parse(body.getString("publicKey"), alg);
                credentialId = DevicePublicKeys.credentialId(DevicePublicKeys.encoded(publicKey));
            } catch (IllegalArgumentException e) {
                badRequest(request, e.getMessage());
                return;
            }

            // La clé candidate n'est pas encore en base : on la décrit comme un enregistrement
            // pour la soumettre exactement aux mêmes contrôles qu'une clé déjà enrôlée.
            final JsonObject candidate = new JsonObject()
                    .put("_id", credentialId)
                    .put("alg", alg)
                    .put("publicKey", DevicePublicKeys.encodeToBase64(publicKey))
                    .put("userId", userInfos.getUserId())
                    .put("deviceId", deviceId);

            final String userId = userInfos.getUserId();
            final String assurance = isSessionProven(session, userInfos)
                    ? DeviceCredentialService.ASSURANCE_PROVEN
                    : DeviceCredentialService.ASSURANCE_SELF;

            DeviceAssertion.verify(assertion, candidate, PURPOSE_ENROL, audienceOf(request), challengeStore)
                    // Une clé enrôlée sur cet appareil n'existe plus côté téléphone : la laisser
                    // active en ferait un facteur fantôme, compté mais inutilisable.
                    .compose(claims -> credentialService.revokeForDevice(userId, deviceId, userId))
                    .compose(revoked -> {
                        final long now = System.currentTimeMillis();
                        final JsonObject credential = candidate.copy()
                                .put("assurance", assurance)
                                .put("proof", DeviceCredentialService.ASSURANCE_PROVEN.equals(assurance)
                                        ? "session" : "password")
                                .put("deviceName", normalizeDeviceName(body.getString("deviceName")))
                                .put("platform", normalizePlatform(body.getString("platform")))
                                .put("createdAt", now)
                                .put("usageCount", 0)
                                .put("revokedAt", (Object) null);
                        return credentialService.create(credential);
                    })
                    .onSuccess(credential -> renderJson(request, new JsonObject()
                            .put("credentialId", credential.getString("_id"))
                            .put("deviceId", credential.getString("deviceId"))
                            .put("assurance", credential.getString("assurance"))
                            .put("createdAt", credential.getLong("createdAt"))))
                    .onFailure(e -> failEnrolment(request, userId, e));
        }));
    }

    /** Clés actives du compte, telles que les affiche la page « mes appareils ». */
    @Get("/device/credentials/mine")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void listMyCredentials(final HttpServerRequest request) {
        withSession(request, (session, userInfos) ->
                credentialService.listActiveFor(userInfos.getUserId())
                        .onSuccess(credentials -> {
                            final JsonArray rendered = new JsonArray();
                            for (Object o : credentials) {
                                if (o instanceof JsonObject) {
                                    rendered.add(publicView((JsonObject) o));
                                }
                            }
                            renderJson(request, new JsonObject()
                                    .put("credentials", rendered)
                                    .put("count", rendered.size()));
                        })
                        .onFailure(e -> {
                            log.error("Error listing device credentials of " + userInfos.getUserId(), e);
                            renderError(request);
                        }));
    }

    /**
     * Retire une clé du compte : c'est le geste à faire quand on perd son téléphone.
     *
     * <p>Exige que le second facteur ait été franchi dans la session courante quand il
     * s'applique au compte. Retirer une clé peut faire disparaître un facteur ; autoriser ce
     * geste sur la foi du seul mot de passe permettrait à qui l'a dérobé de désarmer le compte
     * avant de s'y installer.</p>
     */
    @Delete("/device/credentials/mine/:credentialId")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void revokeMyCredential(final HttpServerRequest request) {
        final String credentialId = request.params().get("credentialId");
        withMfaSession(request, (session, userInfos) ->
                credentialService.revoke(userInfos.getUserId(), credentialId, userInfos.getUserId())
                        .onSuccess(revoked -> {
                            if (Boolean.TRUE.equals(revoked)) {
                                ok(request);
                            } else {
                                // Même réponse pour « clé inconnue » et « clé d'un autre
                                // compte » : cette route n'a pas à renseigner sur les clés d'autrui.
                                notFound(request);
                            }
                        })
                        .onFailure(e -> {
                            log.error("Error revoking device credential " + credentialId, e);
                            renderError(request);
                        }));
    }

    /** Vue rendue à l'utilisateur : tout sauf la clé publique, qui n'a rien à faire à l'écran. */
    private static JsonObject publicView(final JsonObject credential) {
        return new JsonObject()
                .put("credentialId", credential.getString("_id"))
                .put("deviceId", credential.getString("deviceId"))
                .put("deviceName", credential.getString("deviceName"))
                .put("platform", credential.getString("platform"))
                .put("assurance", credential.getString("assurance"))
                .put("createdAt", credential.getLong("createdAt"))
                .put("lastUsedAt", credential.getLong("lastUsedAt"));
    }

    /**
     * Traduit un échec d'enrôlement. Les causes imputables à l'appelant deviennent des 400
     * explicites ; tout le reste est une erreur serveur, et l'assertion rejetée garde son
     * message indifférencié.
     */
    private void failEnrolment(final HttpServerRequest request, final String userId, final Throwable e) {
        final String message = e.getMessage();
        if (DeviceAssertion.REJECTED.equals(message)) {
            renderJson(request, new JsonObject().put("error", message), 401);
            return;
        }
        if (message != null && message.startsWith("device.key.")) {
            badRequest(request, message);
            return;
        }
        log.error("Error confirming device enrolment for " + userId, e);
        renderError(request);
    }

    /**
     * Origine de l'instance, telle qu'elle doit figurer dans la revendication {@code aud}.
     * L'application peut être configurée sur plusieurs ENT : sans ce lien, une assertion
     * obtenue sur l'une serait rejouable sur l'autre.
     */
    private static String audienceOf(final HttpServerRequest request) {
        return Renders.getScheme(request) + "://" + Renders.getHost(request);
    }

    /**
     * Un identifiant d'appareil proposé par le client n'est accepté que s'il a la forme d'un
     * UUID. Il ne sert qu'à regrouper les clés d'un même téléphone pour l'affichage et la
     * révocation, jamais à authentifier : il est toujours confiné au compte de la session.
     */
    private static String acceptableDeviceId(final String proposed) {
        if (isNotEmpty(proposed)) {
            try {
                return UUID.fromString(proposed).toString();
            } catch (IllegalArgumentException ignored) {
                // Valeur inexploitable : on en attribue une neuve plutôt que de refuser.
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String normalizeDeviceName(final String name) {
        if (isEmpty(name)) {
            return null;
        }
        final String trimmed = name.trim();
        return trimmed.length() > DEVICE_NAME_MAX ? trimmed.substring(0, DEVICE_NAME_MAX) : trimmed;
    }

    /** Seules les plateformes que l'on distribue sont retenues, pour ne pas stocker du texte libre. */
    private static String normalizePlatform(final String platform) {
        if ("android".equalsIgnoreCase(platform) || "ios".equalsIgnoreCase(platform)) {
            return platform.toLowerCase();
        }
        return null;
    }

    /**
     * Vrai si la session a établi davantage que le mot de passe. Un compte dispensé de second
     * facteur n'est pas « prouvé » pour autant : la dispense dit qu'on ne l'exige pas, pas
     * qu'une preuve a été apportée.
     */
    private boolean isSessionProven(final JsonObject session, final UserInfos userInfos) {
        return Boolean.TRUE.equals(UserValidation.getIsMFA(eb, session));
    }

    private void withSession(final HttpServerRequest request,
                             final BiConsumer<JsonObject, UserInfos> handler) {
        UserUtils.getSession(eb, request, session -> {
            final UserInfos userInfos = session != null ? UserUtils.sessionToUserInfos(session) : null;
            if (userInfos == null) {
                unauthorized(request);
                return;
            }
            handler.accept(session, userInfos);
        });
    }

    /** Comme {@link #withSession}, mais exige le second facteur lorsqu'il s'applique au compte. */
    private void withMfaSession(final HttpServerRequest request,
                                final BiConsumer<JsonObject, UserInfos> handler) {
        withSession(request, (session, userInfos) -> {
            if (!Mfa.isNotActivatedForUser(userInfos)
                    && !Boolean.TRUE.equals(UserValidation.getIsMFA(eb, session))) {
                renderJson(request, new JsonObject().put("error", "mfa.required"), 403);
                return;
            }
            handler.accept(session, userInfos);
        });
    }

    public void setCredentialService(final DeviceCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    public void setChallengeStore(final DeviceChallengeStore challengeStore) {
        this.challengeStore = challengeStore;
    }

}
