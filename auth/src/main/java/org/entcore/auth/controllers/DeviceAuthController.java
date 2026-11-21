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
import fr.wseduc.webutils.logging.Tracer;
import fr.wseduc.webutils.logging.TracerFactory;
import fr.wseduc.webutils.request.RequestUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.security.ClientDevice;
import org.entcore.auth.security.DeviceAssertion;
import org.entcore.auth.security.DeviceChallengeStore;
import org.entcore.auth.security.DevicePublicKeys;
import org.entcore.auth.security.SessionCookies;
import org.entcore.auth.services.DeviceCredentialService;
import org.entcore.auth.services.DeviceTicketService;
import org.entcore.auth.services.NewDeviceNotifier;
import org.entcore.common.events.EventStore;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.Mfa;
import org.entcore.common.datavalidation.UserValidation;

import java.security.PublicKey;
import java.util.Set;
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

    /** Usage porté par les assertions qui ouvrent une session. */
    private static final String PURPOSE_LOGIN = "login";

    /** Usage porté par les assertions qui apportent le second facteur. */
    private static final String PURPOSE_MFA = "mfa";

    /**
     * Même type d'événement que les connexions par mot de passe : une connexion par appareil
     * reste une connexion, et doit se voir dans les mêmes statistiques.
     */
    private static final String LOGIN_EVENT = "LOGIN";

    /** Longueur maximale d'un nom d'appareil affichable, saisi par l'utilisateur. */
    private static final int DEVICE_NAME_MAX = 64;

    private static final Tracer trace = TracerFactory.getTracer("auth-device");

    private DeviceCredentialService credentialService;
    private DeviceTicketService ticketService;
    private DeviceChallengeStore challengeStore;
    private NewDeviceNotifier newDeviceNotifier;
    private EventStore eventStore;

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
            final String ticket = body.getString("ticket");

            // Niveau d'assurance établi au moment de la confirmation, jamais annoncé d'avance :
            // session déjà passée par le second facteur, ou ticket remis par un administrateur.
            // À défaut, la clé vaut ce que vaut le mot de passe qui l'a posée, et pas davantage.
            final Future<String> assuranceFuture;
            if (isSessionProven(session, userInfos)) {
                assuranceFuture = Future.succeededFuture("session");
            } else if (isNotEmpty(ticket)) {
                assuranceFuture = ticketService.consume(userId, ticket)
                        .map(valid -> valid ? "ticket" : null)
                        .recover(e -> Future.succeededFuture((String) null));
            } else {
                assuranceFuture = Future.succeededFuture("password");
            }

            assuranceFuture.compose(proof -> {
                if (proof == null) {
                    // Ticket présenté mais invalide ou périmé : on refuse plutôt que de retomber
                    // silencieusement sur « self », ce qui laisserait croire l'appareil prouvé.
                    return Future.<JsonObject>failedFuture("device.ticket.rejected");
                }
                return enrol(request, body, candidate, userId, deviceId, proof);
            })
                    .onSuccess(credential -> renderJson(request, new JsonObject()
                            .put("credentialId", credential.getString("_id"))
                            .put("deviceId", credential.getString("deviceId"))
                            .put("assurance", credential.getString("assurance"))
                            .put("createdAt", credential.getLong("createdAt"))))
                    .onFailure(e -> failEnrolment(request, userId, e));
        }));
    }

    /** Vérifie l'assertion d'enrôlement puis enregistre la clé au niveau d'assurance établi. */
    private Future<JsonObject> enrol(final HttpServerRequest request, final JsonObject body,
                                     final JsonObject candidate, final String userId,
                                     final String deviceId, final String proof) {
        final String assurance = "password".equals(proof)
                ? DeviceCredentialService.ASSURANCE_SELF
                : DeviceCredentialService.ASSURANCE_PROVEN;
        return DeviceAssertion.verify(body.getString("assertion"), candidate, PURPOSE_ENROL,
                        audienceOf(request), challengeStore)
                    // Une clé enrôlée sur cet appareil n'existe plus côté téléphone : la laisser
                    // active en ferait un facteur fantôme, compté mais inutilisable.
                    .compose(claims -> credentialService.revokeForDevice(userId, deviceId, userId))
                    .compose(revoked -> {
                        final long now = System.currentTimeMillis();
                        final JsonObject credential = candidate.copy()
                                .put("assurance", assurance)
                                .put("proof", proof)
                                .put("deviceName", normalizeDeviceName(body.getString("deviceName")))
                                .put("platform", normalizePlatform(body.getString("platform")))
                                .put("createdAt", now)
                                .put("usageCount", 0)
                                .put("revokedAt", (Object) null);
                        return credentialService.create(credential);
                    })
                    // L'annuaire doit savoir que ce compte dispose désormais d'un facteur de
                    // possession, sans quoi le second facteur continuerait de l'ignorer.
                    .compose(credential -> projectDeviceMfaFlag(userId).map(unused -> credential));
    }

    /**
     * Délivre à un administrateur un ticket d'enrôlement pour un compte de son périmètre.
     *
     * <p>Ouvre l'enrôlement « prouvé » aux comptes sans adresse e-mail ni numéro, pour qui les
     * deux autres preuves sont hors de portée. Le code s'affiche une seule fois et n'est jamais
     * relisible ; il est tracé nommément, parce qu'un contournement d'authentification doit
     * pouvoir être attribué.</p>
     *
     * <p>Un administrateur n'enrôle jamais à la place de quelqu'un : il ouvre une fenêtre que la
     * personne franchit elle-même, son téléphone en main.</p>
     */
    @Post("/admin/user/:userId/device/ticket")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void issueEnrolmentTicket(final HttpServerRequest request) {
        final String targetUserId = request.params().get("userId");
        if (isEmpty(targetUserId)) {
            badRequest(request);
            return;
        }
        withAdminOnUser(request, targetUserId, admin ->
                ticketService.issue(targetUserId, admin.getUserId())
                        .onSuccess(ticket -> {
                            trace.info(Renders.getIp(request) + " - Ticket d'enregistrement d'appareil délivré pour "
                                    + targetUserId + " par " + admin.getUserId());
                            renderJson(request, ticket);
                        })
                        .onFailure(e -> {
                            log.error("Error issuing device enrolment ticket for " + targetUserId, e);
                            renderError(request);
                        }));
    }

    /**
     * Résout l'appelant et vérifie qu'il administre bien le compte visé. Le périmètre vient de
     * {@link AuthController#adminLocalScope(UserInfos)} : une seconde règle de périmètre finirait
     * par diverger de la première.
     */
    private void withAdminOnUser(final HttpServerRequest request, final String targetUserId,
                                 final java.util.function.Consumer<UserInfos> handler) {
        UserUtils.getUserInfos(eb, request, admin -> {
            if (admin == null || (!admin.isADMC() && !admin.isADML())) {
                unauthorized(request);
                return;
            }
            final Set<String> scope = AuthController.adminLocalScope(admin);
            if (scope == null) {
                // Super-administrateur : aucune restriction d'établissement.
                handler.accept(admin);
                return;
            }
            structuresOf(targetUserId).onSuccess(structures -> {
                for (Object structureId : structures) {
                    if (scope.contains(structureId)) {
                        handler.accept(admin);
                        return;
                    }
                }
                // Hors périmètre et compte inexistant donnent la même réponse : cette route n'a
                // pas à renseigner sur les comptes des autres établissements.
                unauthorized(request);
            }).onFailure(e -> {
                log.error("Error resolving structures of user " + targetUserId, e);
                renderError(request);
            });
        });
    }

    /** Établissements de rattachement d'un compte, pour le contrôle de périmètre. */
    private Future<JsonArray> structuresOf(final String userId) {
        final Promise<JsonArray> promise = Promise.promise();
        Neo4j.getInstance().execute(
                "MATCH (u:User {id: {id}})-[:IN]->(:ProfileGroup)-[:DEPENDS]->(s:Structure) "
                        + "RETURN COLLECT(DISTINCT s.id) as structures",
                new JsonObject().put("id", userId),
                res -> {
                    if (!"ok".equals(res.body().getString("status"))) {
                        promise.fail(res.body().getString("message", "structures.lookup.error"));
                        return;
                    }
                    final JsonArray results = res.body().getJsonArray("result", new JsonArray());
                    if (results.isEmpty()) {
                        promise.complete(new JsonArray());
                        return;
                    }
                    promise.complete(results.getJsonObject(0).getJsonArray("structures", new JsonArray()));
                });
        return promise.future();
    }

    /**
     * Remet un défi à signer pour se connecter. Route publique : l'appelant n'a pas encore de
     * session, c'est précisément ce qu'il cherche à obtenir.
     *
     * <p>Un défi est émis <b>même pour une clé inconnue</b>. Répondre « clé inconnue » ferait de
     * cette route un moyen de vérifier qu'une clé donnée existe sur l'instance ; l'échec vient
     * plus tard, au même titre qu'une signature invalide et avec le même message.</p>
     */
    @Post("/device/challenge")
    public void challenge(final HttpServerRequest request) {
        RequestUtils.bodyToJson(request, body -> {
            final String credentialId = body.getString("credentialId");
            final String purpose = body.getString("purpose", PURPOSE_LOGIN);
            if (!PURPOSE_LOGIN.equals(purpose) && !PURPOSE_MFA.equals(purpose)) {
                badRequest(request, "device.purpose.unsupported");
                return;
            }
            challengeStore.issue(purpose, null, credentialId)
                    .onSuccess(challenge -> renderJson(request, new JsonObject()
                            .put("challenge", challenge)
                            .put("audience", audienceOf(request))
                            .put("purpose", purpose)))
                    .onFailure(e -> {
                        log.error("Error issuing device login challenge", e);
                        renderError(request);
                    });
        });
    }

    /**
     * Ouvre une session sur preuve de possession de l'appareil, sans mot de passe.
     *
     * <p>Remplace le fait de rejouer un mot de passe mémorisé : celui-ci reste un secret
     * <i>rejouable</i>, qui une fois extrait ouvre le compte depuis n'importe où et pour
     * toujours. Une clé non extractible ne peut pas être volée — au mieux obtient-on d'elle des
     * signatures, sur ce téléphone, après déverrouillage biométrique.</p>
     *
     * <p>La session créée est <b>identique</b> à celle d'une connexion par mot de passe : même
     * cookie signé, même durée, même suivi d'appareil. Rien à déconnecter ni à expirer
     * différemment.</p>
     */
    @Post("/device/login")
    public void loginWithDevice(final HttpServerRequest request) {
        RequestUtils.bodyToJson(request, body -> {
            final String assertion = body.getString("assertion");
            final String credentialId = DeviceAssertion.unverifiedKeyId(assertion);
            if (isEmpty(assertion) || isEmpty(credentialId)) {
                rejectLogin(request, null);
                return;
            }
            credentialService.activeById(credentialId)
                    .compose(credential -> {
                        if (credential == null || DeviceCredentialService.isLocked(credential)) {
                            // Clé inconnue, révoquée ou verrouillée : une seule et même réponse.
                            // L'application propose de toute façon le mot de passe en repli, donc
                            // personne ne reste dehors faute d'explication.
                            return Future.<JsonObject>failedFuture(DeviceAssertion.REJECTED);
                        }
                        return DeviceAssertion
                                .verify(assertion, credential, PURPOSE_LOGIN, audienceOf(request), challengeStore)
                                .map(claims -> credential);
                    })
                    .onSuccess(credential -> openSession(request, credential,
                            Boolean.TRUE.equals(body.getBoolean("rememberMe")),
                            Boolean.TRUE.equals(body.getBoolean("secureLocation"))))
                    .onFailure(e -> rejectLogin(request, credentialId));
        });
    }

    /**
     * Crée la session et pose les cookies, exactement comme {@code AuthController.createSession},
     * puis enregistre le passage de l'appareil — ce que les connexions mobiles ne faisaient pas
     * jusqu'ici, faute d'identifiant d'appareil.
     */
    private void openSession(final HttpServerRequest request, final JsonObject credential,
                             final boolean rememberMe, final boolean secureLocation) {
        final String userId = credential.getString("userId");
        final String deviceId = credential.getString("deviceId");
        final JsonObject clientInfos = ClientDevice.infosForDevice(request, deviceId);
        UserUtils.createSession(eb, userId, secureLocation, clientInfos, sessionId -> {
            if (sessionId == null || sessionId.trim().isEmpty()) {
                log.error("Unable to create session for device login of user " + userId);
                renderError(request);
                return;
            }
            SessionCookies.open(request, sessionId, rememberMe, config);
            if (newDeviceNotifier != null) {
                newDeviceNotifier.onSessionCreated(userId, request, clientInfos);
            }
            if (eventStore != null) {
                eventStore.createAndStoreEventByUserId(LOGIN_EVENT, userId, null, request);
            }
            // Trace d'usage : sans incidence sur la connexion qui vient de réussir.
            credentialService.markUsed(credential.getString("_id"));
            renderJson(request, new JsonObject()
                    .put("userId", userId)
                    .put("deviceId", deviceId));
        });
    }

    /** Refus de connexion : même réponse pour toutes les causes, et l'échec est compté. */
    private void rejectLogin(final HttpServerRequest request, final String credentialId) {
        if (credentialId != null) {
            credentialService.recordFailure(credentialId);
        }
        renderJson(request, new JsonObject().put("error", DeviceAssertion.REJECTED), 401);
    }

    /**
     * Apporte le second facteur en prouvant la possession de l'appareil : aucun code à recevoir,
     * donc aucun numéro à détenir ni adresse à renseigner.
     *
     * <p>C'est ce qui rend le second facteur atteignable pour les comptes qui n'ont ni l'un ni
     * l'autre — les élèves de primaire au premier chef, pour qui l'imposer revenait jusqu'ici à
     * les enfermer dehors.</p>
     *
     * <p>Seule une clé enrôlée sous preuve est acceptée : une clé posée avec le seul mot de passe
     * ne prouve rien de plus que lui, et en faire un second facteur transformerait un vol unique
     * en deux facteurs.</p>
     */
    @Post("/device/mfa")
    @SecuredAction(value = "", type = ActionType.AUTHENTICATED)
    public void validateMfaWithDevice(final HttpServerRequest request) {
        withSession(request, (session, userInfos) -> RequestUtils.bodyToJson(request, body -> {
            final String assertion = body.getString("assertion");
            final String credentialId = DeviceAssertion.unverifiedKeyId(assertion);
            if (isEmpty(assertion) || isEmpty(credentialId)) {
                rejectAssertion(request, null);
                return;
            }
            credentialService.activeById(credentialId)
                    .compose(credential -> {
                        if (credential == null || DeviceCredentialService.isLocked(credential)
                                || !DeviceCredentialService.ASSURANCE_PROVEN.equals(credential.getString("assurance"))
                                // La clé doit appartenir au compte de la session : sans ce contrôle,
                                // n'importe quelle clé valide franchirait le facteur de n'importe qui.
                                || !userInfos.getUserId().equals(credential.getString("userId"))) {
                            return Future.<JsonObject>failedFuture(DeviceAssertion.REJECTED);
                        }
                        return DeviceAssertion
                                .verify(assertion, credential, PURPOSE_MFA, audienceOf(request), challengeStore)
                                .map(claims -> credential);
                    })
                    .compose(credential -> UserValidation
                            .setIsMFA(eb, UserUtils.getSessionIdOrTokenId(request).get(), true)
                            .map(set -> credential))
                    .onSuccess(credential -> {
                        credentialService.markUsed(credential.getString("_id"));
                        renderJson(request, new JsonObject().put("state", "valid"));
                    })
                    .onFailure(e -> rejectAssertion(request, credentialId));
        }));
    }

    /** Refus d'une assertion : même réponse pour toutes les causes, et l'échec est compté. */
    private void rejectAssertion(final HttpServerRequest request, final String credentialId) {
        if (credentialId != null) {
            credentialService.recordFailure(credentialId);
        }
        renderJson(request, new JsonObject().put("error", DeviceAssertion.REJECTED), 401);
    }

    /**
     * Reporte dans l'annuaire l'existence d'au moins une clé enrôlée sous preuve.
     *
     * <p>Les clés vivent dans MongoDB, mais la session et la résolution du canal de second
     * facteur se calculent depuis Neo4j. Plutôt que d'interroger deux bases à chaque connexion,
     * on projette un simple drapeau, écrit aux deux seuls moments où il peut changer :
     * l'enrôlement et la révocation.</p>
     */
    private Future<Void> projectDeviceMfaFlag(final String userId) {
        return credentialService.hasProvenFor(userId).compose(hasProven -> {
            final Promise<Void> promise = Promise.promise();
            Neo4j.getInstance().execute(
                    "MATCH (u:User {id: {id}}) SET u.deviceMfa = {flag}",
                    new JsonObject().put("id", userId).put("flag", hasProven),
                    res -> {
                        if (!"ok".equals(res.body().getString("status"))) {
                            // Le drapeau se recalcule au prochain enrôlement ou à la prochaine
                            // révocation : un échec ici ne doit pas faire échouer l'opération.
                            log.error("Unable to project device MFA flag for user " + userId + " : "
                                    + res.body().getString("message"));
                        }
                        promise.complete();
                    });
            return promise.future();
        }).recover(e -> {
            log.error("Unable to resolve proven device keys for user " + userId, e);
            return Future.succeededFuture();
        });
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
                        // Retirer la dernière clé prouvée retire aussi un facteur : l'annuaire doit
                        // le savoir, sinon le compte resterait soumis à un second facteur devenu
                        // inatteignable.
                        .compose(revoked -> projectDeviceMfaFlag(userInfos.getUserId()).map(u -> revoked))
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

    public void setTicketService(final DeviceTicketService ticketService) {
        this.ticketService = ticketService;
    }

    public void setChallengeStore(final DeviceChallengeStore challengeStore) {
        this.challengeStore = challengeStore;
    }

    public void setNewDeviceNotifier(final NewDeviceNotifier newDeviceNotifier) {
        this.newDeviceNotifier = newDeviceNotifier;
    }

    public void setEventStore(final EventStore eventStore) {
        this.eventStore = eventStore;
    }

}
