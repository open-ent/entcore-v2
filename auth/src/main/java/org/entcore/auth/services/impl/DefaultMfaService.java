package org.entcore.auth.services.impl;

import fr.wseduc.webutils.Server;
import fr.wseduc.webutils.email.EmailSender;
import fr.wseduc.webutils.http.Renders;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import org.entcore.auth.services.MfaService;
import org.entcore.common.datavalidation.UserValidation;
import org.entcore.common.datavalidation.impl.AbstractDataValidationService;
import org.entcore.common.datavalidation.metrics.DataValidationMetricsFactory;
import org.entcore.common.datavalidation.utils.DataStateUtils;
import org.entcore.common.email.EmailFactory;
import org.entcore.common.events.EventStore;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.sms.SmsSender;
import org.entcore.common.sms.SmsSenderFactory;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;
import org.entcore.common.utils.Mfa;
import org.entcore.common.utils.StringUtils;

import fr.wseduc.webutils.Either;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.entcore.common.datavalidation.utils.DataStateUtils.*;
import static org.entcore.common.neo4j.Neo4jResult.validUniqueResult;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;


public class DefaultMfaService implements MfaService {
    static Logger logger = LoggerFactory.getLogger(DefaultMfaService.class);
    /** Pas des clés matérielles enregistrées par un administrateur (comportement amont). */
    static final int HARDWARE_TOTP_PERIOD = 60;
    /** Pas des applications d'authentification (Google Authenticator, FreeOTP…) : 30 s, valeur usuelle de la RFC 6238. */
    static final int APP_TOTP_PERIOD = 30;
    /** Durée laissée pour scanner le QR code puis saisir le premier code. */
    private static final long TOTP_ENROLLMENT_TTL_MS = 15 * 60 * 1000L;
    private static final int TOTP_ENROLLMENT_TRIES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Inner service to manage field "mfaState" */
    //---------------------------------------------------------------
    private class MfaField extends AbstractDataValidationService {
    //---------------------------------------------------------------
        private SmsSender sms;
        private EmailSender emailSender;
        public EventStore eventStore;

        MfaField(io.vertx.core.Vertx vertx, io.vertx.core.json.JsonObject config, io.vertx.core.json.JsonObject params) {
            super(
                "email", // used for reading only, see retrieveFullState
                "mfaState",
                vertx,
                config,
                params
            );
        }

        /**
         * Lit d'un coup tout ce qui décide du canal : secret TOTP, adresse e-mail, mobile, et le
         * mode choisi par la personne (`u.mfaType`).
         */
        @Override
        protected Future<JsonObject> retrieveFullState(String userId) {
            final Promise<JsonObject> promise = Promise.promise();
            String query =
                "MATCH (u:`User` { id : {id}}) " +
                "RETURN u.totp as totp, u.totpPeriod as totpPeriod, u.mobile as mobile, u.email as email, " +
                "u.mfaType as mfaType, COALESCE(u." + stateField + ", null) as " + stateField + ", " +
                "u.firstName as firstName, u.lastName as lastName, u.displayName as displayName";
            neo.execute(query, new JsonObject().put("id", userId), m -> {
                Either<String, JsonObject> r = validUniqueResult(m);
                if (r.isRight()) {
                    final JsonObject result = r.right().getValue();
                    result.put(stateField, fromRaw(result.getString(stateField)));
                    promise.complete(result);
                } else {
                    promise.fail(r.left().getValue());
                }
            });
            return promise.future();
        }

        /**
         * @return MFA field metadata : state, names, channel and target email or phone number
         * { state: JsonObject|null, firstName:string, lastName:string, displayName:string,
         *   channel: "totp"|"email"|"sms", target:string|null, totp:string|null, totpPeriod:number|null }
         */
        protected Future<JsonObject> getCurrentMfaState(final String userId) {
            return retrieveFullState(userId)
            .compose( j -> {
                JsonObject state = j.getJsonObject(stateField);

                // Check business rules
                do {
                    if( state == null ) {
                        state = new JsonObject();
                        setState(state, OUTDATED);
                        j.put(stateField, state);
                        break;
                    }
                    // A MFA is never validated in the database ! Only in memory.
                    if( getState(state) == VALID ) {
                        // should never happen
                        setState(state, OUTDATED);
                        break;
                    }
                    // if TTL or max tries reached => outdated code
                    if (getState(state) == OUTDATED) {
                        break;
                    }
                    // Check time to live
                    Long ttl = getTtl(state);
                    if( ttl==null || ttl.compareTo(System.currentTimeMillis()) < 0 ) {
                        // TTL reached
                        setState(state, OUTDATED);
                        break;
                    }
                    // Check remaining tries
                    Integer tries = getTries(state);
                    if( tries==null || tries <= 0 ) {
                        setState(state, OUTDATED);
                        break;
                    }
                    // Otherwise, current code is still pending.
                } while(false);
                j.put("state", j.remove(stateField) );
                final String channel = resolveChannel(j.getString("mfaType"),
                        !StringUtils.isEmpty(j.getString("totp")),
                        !StringUtils.isEmpty(j.getString("email")),
                        !StringUtils.isEmpty(j.getString("mobile")));
                j.put("channel", channel);
                j.put("target", targetOf(j, channel));
                return Future.succeededFuture(j);
            });
        }

        protected JsonObject formatAsResponse(final int state, final Integer tries, final Long ttl) {
            return formatAsResponse(state, null, tries, ttl);
        }

        protected Future<JsonObject> generateOrRefreshCode(
            final JsonObject state, String userId, final long validDurationS, final int triesLimit
            ) {
            if(state != null && getState(state) != OUTDATED) {
                // Refresh this pending code
                setState(state, PENDING);
                //setKey(state, generateRandomCode());  // keep same code
                setTtl(state, System.currentTimeMillis() + validDurationS * 1000l);
                setTries(state, triesLimit);
                return updateState(userId, state);
            } else {
                // Generate a new code
                return mfaField.startUpdate(
                    userId,
                    null,
                    UserValidation.getDefaultTtlInSeconds(),
                    UserValidation.getDefaultRetryNumber()
                );
            }
        }

        @Override
        public Future<JsonObject> startUpdate(String userId, String unused, final long validDurationS, final int triesLimit) {
            // A new code is needed.
            final JsonObject state = new JsonObject();
            setState(state, PENDING);
            setKey(state, generateRandomCode());
            setTtl(state, System.currentTimeMillis() + validDurationS * 1000l);
            setTries(state, triesLimit);
            return updateState(userId, state);
        }

        protected Future<JsonObject> outdateCode(final String userId, final JsonObject state) {
            setState(state, OUTDATED);
            setTtl(state, System.currentTimeMillis());
            return updateState(userId, state);
        }

        @Override
        public Future<JsonObject> tryValidate(String userId, String code) {
            return getCurrentMfaState(userId)
            .map( j -> {
                return j.getJsonObject("state");
            })
            .compose( state -> {
                // Check business rules
                do {
                    if( state == null ) {
                        // Code is outdated
                        state = new JsonObject();
                        setState(state, OUTDATED);
                        break;
                    }
                    // if TTL or max tries reached, then don't check code
                    if (getState(state) == OUTDATED) {
                        break;
                    }
                    // Check code
                    String key = StringUtils.trimToNull( getKey(state) );
                    if( key == null || !key.equals(StringUtils.trimToNull(code)) ) {
                        // Invalid
                        Integer tries = getTries(state);
                        if(tries==null) {
                            tries = 0;
                        } else {
                            tries = Math.max(0, tries.intValue() - 1 );
                        }
                        if( tries <= 0 ) {
                            setState(state, OUTDATED);
                        }
                        setTries(state, tries);
                        break;
                    }

                    // ---Validation succeeded---
                    // Clean data in neo4j, and remember an MFA was done successfully.
                    return updateState(userId, null)
                    .map( unused -> {
                        return formatAsResponse(VALID, null, null);
                    });
                } while(false);

                // ---Validation results---
                return updateState(userId, state)
                .map( newState -> {
                    return formatAsResponse(getState(newState), getTries(newState), getTtl(newState));
                });
            });
        }

        @Override
        public Future<JsonObject> hasValid(String userId) {
            return Future.failedFuture("No data to check");
        }

        @Override
        public Future<String> sendValidationMessage( final HttpServerRequest request, String target,
                                                     final JsonObject templateParams,
                                                     final String module) {
            return sendCode(request, Mfa.withSms() ? Mfa.TYPE_SMS : Mfa.TYPE_EMAIL, target, templateParams, module);
        }

        /** Envoie le code par le canal résolu pour la personne. */
        protected Future<String> sendCode(final HttpServerRequest request, final String channel, final String target,
                                          final JsonObject templateParams, final String module) {
            if( StringUtils.isEmpty(target) ) {
                logger.info("[2FA] Cannot send a code through sms or email, since target is empty !");
                return Future.failedFuture("empty.target");
            }
            if( Mfa.TYPE_SMS.equals(channel) ) {
                return sms.sendUnique(request, target, "phone/mfaCode.txt", templateParams, module);
            }
            if( Mfa.TYPE_EMAIL.equals(channel) ) {
                // Panne d'envoi (serveur SMTP injoignable…) : la cause technique part au journal,
                // l'appelant reçoit un code d'erreur stable qu'il sait afficher.
                return sendTemplatedEmail(request, target, "mfa.email.subject", "email/mfaCode.html", templateParams)
                    .recover( t -> {
                        logger.error("[2FA] Code not sent by email: " + t.getMessage());
                        return Future.failedFuture("send.failed");
                    });
            }
            return Future.failedFuture("not.implemented.yet");
        }

        protected Future<String> sendEmail(final HttpServerRequest request, final String to, final String subject,
                                           final String templateName, final JsonObject templateParams) {
            final Promise<String> promise = Promise.promise();
            if (emailSender == null) {
                emailSender = EmailFactory.getInstance().getSenderWithPriority(EmailFactory.PRIORITY_HIGH);
            }
            if (emailSender == null) {
                promise.fail("invalid.provider");
            } else if (StringUtils.isEmpty(to)) {
                promise.fail("empty.target");
            } else {
                processEmailTemplate(request, templateParams, templateName, false)
                .onFailure(promise::fail)
                .onSuccess( processedTemplate -> emailSender.sendEmail(
                        request, to, null, null, subject, processedTemplate, null, false,
                        ar -> {
                            if (ar.succeeded()) {
                                final Message<JsonObject> reply = ar.result();
                                if ("ok".equals(reply.body().getString("status"))) {
                                    promise.complete("");
                                } else {
                                    promise.fail(reply.body().getString("message", ""));
                                }
                            } else {
                                promise.fail(ar.cause().getMessage());
                            }
                        }));
            }
            return promise.future();
        }

        protected Future<String> sendTemplatedEmail(final HttpServerRequest request, final String to, final String subjectKey,
                                                    final String templateName, final JsonObject templateParams) {
            return formatEmailSubject(request, subjectKey, templateParams)
                .compose( subject -> sendEmail(request, to, subject, templateName, templateParams) );
        }

        @Override
        public Future<String> sendWarningMessage(HttpServerRequest request, Map<String, String> targets, JsonObject templateParams) {
            return Future.succeededFuture();
        }
    }

    //---------------------------------------------------------------
    private MfaField mfaField = null;
    private EventBus eb = null;

    public DefaultMfaService(final Vertx vertx, final io.vertx.core.json.JsonObject config, Map<String, Object> server)
            throws InvalidKeyException {
		io.vertx.core.json.JsonObject params = config.getJsonObject("emailValidationConfig");
		if (params == null ) {
			String s = (String) server.get("emailValidationConfig");
			params = (s != null) ? new JsonObject(s) : new JsonObject();
		}

        // The encryptKey parameter must be defined correctly.
        String encryptKey = params.getString("encryptKey", null);
        if( encryptKey != null
                    && (encryptKey.length()!=16 && encryptKey.length()!=24 && encryptKey.length()!=32) ) {
            // An AES key has to be 16, 24 or 32 bytes long.
            throw new InvalidKeyException("The \"encryptKey\" parameter must be 16, 24 or 32 bytes long.");
        }

        mfaField = new MfaField(vertx, config, params);
        eb = Server.getEventBus(vertx);
        DataValidationMetricsFactory.init(vertx, config);
    }

    /**
     * Canal de second facteur d'un compte.
     * Le mode choisi par la personne (`u.mfaType`) l'emporte s'il est configuré sur la plateforme et
     * utilisable (secret TOTP enregistré, adresse e-mail ou mobile renseigné). Sinon, ordre amont :
     * clé TOTP enregistrée, puis SMS, puis e-mail.
     */
    public static String resolveChannel(final String preferred, final boolean hasTotp, final boolean hasEmail, final boolean hasMobile) {
        final boolean totpUsable = Mfa.withTotp() && hasTotp;
        final boolean smsUsable = Mfa.withSms() && hasMobile;
        final boolean emailUsable = Mfa.withEmail() && hasEmail;
        if (Mfa.TYPE_TOTP.equals(preferred) && totpUsable) return Mfa.TYPE_TOTP;
        if (Mfa.TYPE_EMAIL.equals(preferred) && emailUsable) return Mfa.TYPE_EMAIL;
        if (Mfa.TYPE_SMS.equals(preferred) && smsUsable) return Mfa.TYPE_SMS;
        if (totpUsable) return Mfa.TYPE_TOTP;
        if (smsUsable) return Mfa.TYPE_SMS;
        if (emailUsable) return Mfa.TYPE_EMAIL;
        // Aucun canal utilisable : on garde un canal à code, l'envoi échouera sur "empty.target".
        return Mfa.withSms() ? Mfa.TYPE_SMS : Mfa.TYPE_EMAIL;
    }

    private static String targetOf(final JsonObject user, final String channel) {
        if (Mfa.TYPE_EMAIL.equals(channel)) return user.getString("email");
        if (Mfa.TYPE_SMS.equals(channel)) return user.getString("mobile");
        return null;
    }

    /** Masque une adresse e-mail ou un numéro pour l'affichage : `j•••@exemple.fr`, `••••••42`. */
    public static String maskTarget(final String channel, final String target) {
        if (StringUtils.isEmpty(target)) return null;
        if (Mfa.TYPE_EMAIL.equals(channel)) {
            final int at = target.indexOf('@');
            if (at <= 0) return "•••";
            return target.charAt(0) + "•••" + target.substring(at);
        }
        final String digits = target.replaceAll("[^0-9]", "");
        if (digits.length() <= 2) return "••";
        return "••••••" + digits.substring(digits.length() - 2);
    }

    private static int periodOf(final JsonObject user) {
        final Integer period = user.getInteger("totpPeriod");
        return (period != null && period > 0) ? period : HARDWARE_TOTP_PERIOD;
    }

    private JsonObject templateParams(final HttpServerRequest request, final UserInfos userInfos) {
        return new JsonObject()
            .put("scheme", Renders.getScheme(request))
            .put("host", Renders.getHost(request))
            .put("userId", userInfos.getUserId())
            .put("firstName", userInfos.getFirstName())
            .put("lastName", userInfos.getLastName())
            .put("userName", userInfos.getUsername());
    }

    public Future<JsonObject> startMfaWorkflow(final HttpServerRequest request, final JsonObject session, final UserInfos userInfos) {
        return startMfaWorkflow(request, session, userInfos, null);
    }

    @Override
    public Future<JsonObject> startMfaWorkflow(final HttpServerRequest request, final JsonObject session,
                                               final UserInfos userInfos, final String requestedChannel) {
        if( Mfa.isNotActivatedForUser(userInfos) ) {
            // Mfa deactivated => error
            return Future.failedFuture("not.active");
        }
        // At first, retrieve current state and the channel of this user
        return mfaField.getCurrentMfaState(userInfos.getUserId())
        .compose( fullState -> {
            String channel = fullState.getString("channel");
            // Repli demandé depuis la page de validation (téléphone perdu…) : un code à usage unique
            // par e-mail ou SMS, si ce canal est configuré et renseigné pour le compte.
            if( requestedChannel != null && !requestedChannel.equals(channel) ) {
                final boolean usable =
                    (Mfa.TYPE_EMAIL.equals(requestedChannel) && Mfa.withEmail() && !StringUtils.isEmpty(fullState.getString("email")))
                    || (Mfa.TYPE_SMS.equals(requestedChannel) && Mfa.withSms() && !StringUtils.isEmpty(fullState.getString("mobile")));
                if( !usable ) {
                    return Future.failedFuture("channel.unavailable");
                }
                channel = requestedChannel;
            }
            final String sendChannel = channel;
            final String target = targetOf(fullState, sendChannel);
            // For users with TOTP enrolled, no code needs to be generated or sent
            if( Mfa.TYPE_TOTP.equals(sendChannel) ) {
                return Future.succeededFuture(new JsonObject().put("state", "pending").put("type", Mfa.TYPE_TOTP));
            }
            // For SMS/email users: generate a new code, or refresh it if pending.
            return mfaField.generateOrRefreshCode(
                fullState.getJsonObject("state"),
                userInfos.getUserId(),
                UserValidation.getDefaultTtlInSeconds(),
                UserValidation.getDefaultRetryNumber()
            ).compose( mfaState -> {
                if( getState(mfaState) != PENDING ) {
                    // If code is not pending, something went wrong => do nothing more.
                    return Future.succeededFuture(mfaState);
                } else {
                    // If code is pending, send it by email or sms.
                    final Long expires = getOrElse(getTtl(mfaState), System.currentTimeMillis() + UserValidation.getDefaultWaitInSeconds()*1000l);

                    JsonObject templateParams = templateParams(request, userInfos)
                    .put("duration", Math.round(DataStateUtils.ttlToRemainingSeconds(expires) / 60f))
                    .put("code", DataStateUtils.getKey(mfaState));
                    return mfaField.sendCode(request, sendChannel, target, templateParams, "MFA")
                    .onFailure( t -> {
                        // Code was not sent => consider it is outdated
                        logger.error(t);
                        mfaField.outdateCode(userInfos.getUserId(), mfaState);
                    })
                    .map( msgId -> {
                        // Code was sent => this is a metric to follow
                        DataValidationMetricsFactory.getRecorder().onMfaCodeGenerated();
                        return mfaState;
                    });
                }
            })
            .map( state -> {
                return mfaField.formatAsResponse(getState(state), getTries(state), getTtl(state))
                    .put("type", sendChannel)
                    .put("target", maskTarget(sendChannel, target));
            });
        });
    }

    public Future<JsonObject> tryCode(final HttpServerRequest request, final UserInfos userInfos, final String key) {
        if( Mfa.isNotActivatedForUser(userInfos) ) {
            // Mfa deactivated => error
            return Future.failedFuture("validate-mfa.error.not.active");
        }
        // Bifurcation par compte : application/clé TOTP, ou code envoyé par e-mail/SMS
        final Future<JsonObject> validationResult = mfaField.getCurrentMfaState(userInfos.getUserId())
        .compose( fullState -> {
            if( !Mfa.TYPE_TOTP.equals(fullState.getString("channel")) ) {
                return mfaField.tryValidate(userInfos.getUserId(), key);
            }
            return verifyTotp(fullState.getString("totp"), periodOf(fullState), key)
            .compose( totpResult -> {
                // Un code de repli envoyé par e-mail/SMS est en cours : il reste accepté.
                if( !"valid".equals(totpResult.getString("state"))
                        && getState(fullState.getJsonObject("state")) == PENDING ) {
                    return mfaField.tryValidate(userInfos.getUserId(), key);
                }
                return Future.succeededFuture(totpResult);
            });
        });
        return validationResult.compose( result -> {
            if( result !=null && "valid".equalsIgnoreCase(result.getString("state")) ) {
                return UserValidation.setIsMFA(eb, UserUtils.getSessionIdOrTokenId(request).get(), true)
                .map( set -> result )
                // Code was consumed => this is a metric to follow
                .onComplete( ar -> DataValidationMetricsFactory.getRecorder().onMfaCodeConsumed() );
            }
            return Future.succeededFuture(result);
        });
    }

    private Future<JsonObject> verifyTotp(final String totpSecretB64, final String code) {
        return verifyTotp(totpSecretB64, HARDWARE_TOTP_PERIOD, code);
    }

    private Future<JsonObject> verifyTotp(final String totpSecretB64, final int periodSeconds, final String code) {
        try {
            final TimeBasedOneTimePasswordGenerator generator =
                new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(periodSeconds));
            final byte[] secretBytes = Base64.getDecoder().decode(totpSecretB64);
            final SecretKeySpec secretKey = new SecretKeySpec(secretBytes, generator.getAlgorithm());
            final String trimmed = StringUtils.trimToNull(code);
            final Instant now = Instant.now();
            for (int window = -1; window <= 1; window++) {
                final Instant t = now.plus(generator.getTimeStep().multipliedBy(window));
                if (generator.generateOneTimePasswordString(secretKey, t).equals(trimmed)) {
                    return Future.succeededFuture(new JsonObject().put("state", "valid"));
                }
            }
            return Future.succeededFuture(new JsonObject().put("state", "invalid"));
        } catch (Exception e) {
            logger.error("[TOTP] Verification error", e);
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<JsonObject> verifyTotpForUser(final String userId, final String code) {
        final Promise<JsonObject> promise = Promise.promise();
        final String query =
            "MATCH (u:User {id:{id}}) RETURN u.totp as totp, u.totpPeriod as totpPeriod";
        Neo4j.getInstance().execute(query, new JsonObject().put("id", userId), res -> {
            if (!"ok".equals(res.body().getString("status"))) {
                promise.fail(res.body().getString("message", "neo4j.error"));
                return;
            }
            final JsonArray results = res.body().getJsonArray("result");
            if (results == null || results.isEmpty()) {
                promise.fail("user.not.found");
                return;
            }
            final JsonObject user = results.getJsonObject(0);
            final String totpSecret = user.getString("totp");
            if (StringUtils.isEmpty(totpSecret)) {
                promise.complete(new JsonObject().put("state", "not.enrolled"));
                return;
            }
            verifyTotp(totpSecret, periodOf(user), code).onComplete(promise);
        });
        return promise.future();
    }

    //---------------------------------------------------------------
    // Réglages du second facteur, à la main du titulaire du compte
    //---------------------------------------------------------------

    private static Future<JsonObject> queryUnique(final String query, final JsonObject params) {
        final Promise<JsonObject> promise = Promise.promise();
        Neo4j.getInstance().execute(query, params, m -> {
            final Either<String, JsonObject> r = validUniqueResult(m);
            if (r.isRight()) {
                promise.complete(r.right().getValue());
            } else {
                promise.fail(r.left().getValue());
            }
        });
        return promise.future();
    }

    private static Future<JsonObject> readSettings(final String userId) {
        return queryUnique(
            "MATCH (u:User {id:{id}}) RETURN u.email as email, u.mobile as mobile, u.mfaType as mfaType, " +
            "CASE WHEN u.totp IS NOT NULL AND u.totp <> '' THEN true ELSE false END as hasTotp, " +
            "COALESCE(u.totpSelf, false) as totpSelf, u.totpPending as totpPending, u.login as login, " +
            "u.totpDevice as totpDevice, u.totpEnrolledAt as totpEnrolledAt",
            new JsonObject().put("id", userId));
    }

    private static JsonArray configuredTypes() {
        final JsonArray types = new JsonArray();
        if (Mfa.withEmail()) types.add(Mfa.TYPE_EMAIL);
        if (Mfa.withSms()) types.add(Mfa.TYPE_SMS);
        if (Mfa.withTotp()) types.add(Mfa.TYPE_TOTP);
        return types;
    }

    @Override
    public Future<JsonObject> getSettings(final UserInfos userInfos) {
        return readSettings(userInfos.getUserId()).map( u -> {
            final boolean hasTotp = Boolean.TRUE.equals(u.getBoolean("hasTotp"));
            final String email = u.getString("email");
            final String mobile = u.getString("mobile");
            final String channel = resolveChannel(u.getString("mfaType"), hasTotp,
                    !StringUtils.isEmpty(email), !StringUtils.isEmpty(mobile));
            return new JsonObject()
                .put("types", configuredTypes())
                .put("active", !Mfa.isNotActivatedForUser(userInfos))
                .put("preferred", u.getString("mfaType"))
                .put("channel", configuredTypes().isEmpty() ? null : channel)
                .put("hasTotp", hasTotp)
                // Une clé matérielle remise par l'administration ne se retire pas depuis le compte.
                .put("totpManagedByAdmin", hasTotp && !Boolean.TRUE.equals(u.getBoolean("totpSelf")))
                .put("totpDevice", hasTotp ? u.getString("totpDevice") : null)
                .put("totpEnrolledAt", hasTotp ? u.getLong("totpEnrolledAt") : null)
                .put("email", maskTarget(Mfa.TYPE_EMAIL, email))
                .put("mobile", maskTarget(Mfa.TYPE_SMS, mobile));
        });
    }

    @Override
    public Future<JsonObject> setPreferredType(final UserInfos userInfos, final String type) {
        if (type == null || !configuredTypes().contains(type)) {
            return Future.failedFuture("mfa.type.unavailable");
        }
        return readSettings(userInfos.getUserId()).compose( u -> {
            if (Mfa.TYPE_TOTP.equals(type) && !Boolean.TRUE.equals(u.getBoolean("hasTotp"))) {
                return Future.failedFuture("mfa.totp.not.enrolled");
            }
            if (Mfa.TYPE_EMAIL.equals(type) && StringUtils.isEmpty(u.getString("email"))) {
                return Future.failedFuture("mfa.email.missing");
            }
            if (Mfa.TYPE_SMS.equals(type) && StringUtils.isEmpty(u.getString("mobile"))) {
                return Future.failedFuture("mfa.mobile.missing");
            }
            return queryUnique("MATCH (u:User {id:{id}}) SET u.mfaType = {type} RETURN u.id as id",
                    new JsonObject().put("id", userInfos.getUserId()).put("type", type));
        })
        .compose( unused -> getSettings(userInfos) );
    }

    /**
     * Enregistrement d'une application d'authentification.
     *
     * Un nouvel appareil ne s'enregistre qu'avec la preuve d'un facteur déjà possédé : soit la
     * session a franchi le second facteur (`sessionProven`), soit un code est envoyé à l'adresse
     * e-mail du compte et devra accompagner le premier code de l'application. Sans cela, le seul
     * mot de passe suffirait à associer le téléphone d'un tiers au compte — y compris sur un compte
     * encore dispensé, qui le retrouverait le jour où le second facteur lui serait imposé.
     * Sans adresse e-mail ni session vérifiée, l'enregistrement passe par un administrateur.
     */
    @Override
    public Future<JsonObject> startTotpEnrollment(final HttpServerRequest request, final UserInfos userInfos,
                                                  final String issuer, final String deviceName, final boolean sessionProven) {
        if (!Mfa.withTotp()) {
            return Future.failedFuture("mfa.type.unavailable");
        }
        return readSettings(userInfos.getUserId()).compose( u -> {
            if (Boolean.TRUE.equals(u.getBoolean("hasTotp")) && !Boolean.TRUE.equals(u.getBoolean("totpSelf"))) {
                return Future.failedFuture("mfa.totp.managed.by.admin");
            }
            final String email = u.getString("email");
            if (!sessionProven && StringUtils.isEmpty(email)) {
                return Future.failedFuture("mfa.proof.unavailable");
            }
            final byte[] secret = new byte[20];
            RANDOM.nextBytes(secret);
            final JsonObject pending = new JsonObject()
                .put("secret", Base64.getEncoder().encodeToString(secret))
                .put("expires", System.currentTimeMillis() + TOTP_ENROLLMENT_TTL_MS)
                .put("tries", TOTP_ENROLLMENT_TRIES)
                .put("proof", sessionProven ? "session" : Mfa.TYPE_EMAIL)
                .put("device", normalizeDeviceName(deviceName));
            final String base32 = base32(secret);
            final String account = getOrElse(u.getString("login"), userInfos.getLogin());
            final String uri = "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + base32 + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=" + APP_TOTP_PERIOD;
            final JsonObject response = new JsonObject()
                .put("secret", base32)
                .put("uri", uri)
                .put("period", APP_TOTP_PERIOD)
                .put("expiresIn", TOTP_ENROLLMENT_TTL_MS / 1000);
            final Future<Void> proof = sessionProven
                ? Future.succeededFuture()
                : sendEnrollmentProofCode(request, userInfos, email)
                    .recover( t -> Future.failedFuture("mfa.proof.send.failed") )
                    .map( unused -> {
                    response.put("proof", Mfa.TYPE_EMAIL).put("proofTarget", maskTarget(Mfa.TYPE_EMAIL, email));
                    return (Void) null;
                });
            return proof
                .compose( unused -> queryUnique("MATCH (u:User {id:{id}}) SET u.totpPending = {pending} RETURN u.id as id",
                    new JsonObject().put("id", userInfos.getUserId()).put("pending", pending.encode())))
                .map( unused -> response );
        });
    }

    /** Code à usage unique envoyé à l'adresse e-mail du compte (même mécanisme que le second facteur par e-mail). */
    private Future<Void> sendEnrollmentProofCode(final HttpServerRequest request, final UserInfos userInfos, final String email) {
        return mfaField.startUpdate(userInfos.getUserId(), null,
                UserValidation.getDefaultTtlInSeconds(), UserValidation.getDefaultRetryNumber())
            .compose( state -> {
                final JsonObject params = templateParams(request, userInfos)
                    .put("duration", Math.round(DataStateUtils.ttlToRemainingSeconds(getTtl(state)) / 60f))
                    .put("code", getKey(state));
                return mfaField.sendCode(request, Mfa.TYPE_EMAIL, email, params, "MFA");
            })
            .mapEmpty();
    }

    private static String normalizeDeviceName(final String deviceName) {
        final String name = StringUtils.trimToNull(deviceName);
        if (name == null) return null;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    @Override
    public Future<JsonObject> confirmTotpEnrollment(final HttpServerRequest request, final UserInfos userInfos,
                                                    final String code, final String emailCode, final boolean sessionProven) {
        return readSettings(userInfos.getUserId()).compose( u -> {
            final String raw = u.getString("totpPending");
            if (StringUtils.isEmpty(raw)) {
                return Future.failedFuture("mfa.totp.no.pending");
            }
            final JsonObject pending = new JsonObject(raw);
            final int tries = pending.getInteger("tries", 0);
            if (pending.getLong("expires", 0L) < System.currentTimeMillis() || tries <= 0) {
                return clearPending(userInfos.getUserId())
                    .compose( unused -> Future.failedFuture("mfa.totp.no.pending") );
            }
            final String secret = pending.getString("secret");
            // Le code de l'application d'abord : il ne consomme rien. Le code e-mail, lui, est à usage
            // unique — le vérifier en premier le brûlerait à chaque faute de frappe sur l'autre.
            return verifyTotp(secret, APP_TOTP_PERIOD, code).compose( result -> {
                if (!"valid".equals(result.getString("state"))) {
                    pending.put("tries", tries - 1);
                    return queryUnique("MATCH (u:User {id:{id}}) SET u.totpPending = {pending} RETURN u.id as id",
                            new JsonObject().put("id", userInfos.getUserId()).put("pending", pending.encode()))
                        .map( unused -> new JsonObject().put("state", "invalid").put("field", "code").put("tries", tries - 1) );
                }
                final boolean needsEmailProof = !sessionProven && !"session".equals(pending.getString("proof"));
                final Future<JsonObject> proof = needsEmailProof
                    ? mfaField.tryValidate(userInfos.getUserId(), emailCode)
                    : Future.succeededFuture(new JsonObject().put("state", "valid"));
                return proof.compose( proofResult -> {
                    if (!"valid".equals(proofResult.getString("state"))) {
                        return Future.succeededFuture(new JsonObject()
                            .put("state", "invalid")
                            .put("field", "emailCode")
                            .put("outdated", "outdated".equals(proofResult.getString("state"))));
                    }
                    final JsonObject params = new JsonObject().put("id", userInfos.getUserId()).put("secret", secret)
                        .put("period", APP_TOTP_PERIOD)
                        .put("device", pending.getString("device"))
                        .put("now", System.currentTimeMillis());
                    return queryUnique(
                            "MATCH (u:User {id:{id}}) " +
                            "SET u.totp = {secret}, u.totpPeriod = {period}, u.totpSelf = true, u.mfaType = 'totp', " +
                            "u.totpDevice = {device}, u.totpEnrolledAt = {now} " +
                            "REMOVE u.totpPending RETURN u.email as email",
                            params)
                        .map( saved -> {
                            notifyTotpEnrolled(request, userInfos, saved.getString("email"), pending.getString("device"));
                            return new JsonObject().put("state", "valid");
                        });
                });
            });
        });
    }

    /** Prévient par e-mail qu'une application d'authentification vient d'être associée au compte. */
    private void notifyTotpEnrolled(final HttpServerRequest request, final UserInfos userInfos, final String email,
                                    final String device) {
        if (StringUtils.isEmpty(email)) return;
        final JsonObject params = templateParams(request, userInfos).put("device", getOrElse(device, ""));
        mfaField.sendTemplatedEmail(request, email, "mfa.totp.enrolled.subject", "email/mfaTotpEnrolled.html", params)
            .onFailure( t -> logger.warn("[TOTP] Enrollment notification not sent: " + t.getMessage()) );
    }

    private static Future<JsonObject> clearPending(final String userId) {
        return queryUnique("MATCH (u:User {id:{id}}) REMOVE u.totpPending RETURN u.id as id",
                new JsonObject().put("id", userId));
    }

    @Override
    public Future<JsonObject> removeTotp(final UserInfos userInfos) {
        return readSettings(userInfos.getUserId()).compose( u -> {
            if (Boolean.TRUE.equals(u.getBoolean("hasTotp")) && !Boolean.TRUE.equals(u.getBoolean("totpSelf"))) {
                return Future.failedFuture("mfa.totp.managed.by.admin");
            }
            return queryUnique(
                "MATCH (u:User {id:{id}}) " +
                "SET u.mfaType = CASE WHEN u.mfaType = 'totp' THEN null ELSE u.mfaType END " +
                "REMOVE u.totp, u.totpPeriod, u.totpSelf, u.totpPending, u.totpDevice, u.totpEnrolledAt RETURN u.id as id",
                new JsonObject().put("id", userInfos.getUserId()));
        })
        .compose( unused -> getSettings(userInfos) );
    }

    private static String urlEncode(final String value) {
        try {
            return URLEncoder.encode(getOrElse(value, ""), StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /** Encodage Base32 (RFC 4648, sans remplissage), format attendu par les applications d'authentification. */
    public static String base32(final byte[] bytes) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        final StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >>> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

	public DefaultMfaService setEventStore(EventStore eventStore) {
		this.mfaField.eventStore = eventStore;
        this.mfaField.sms = SmsSenderFactory.getInstance().newInstance(eventStore );
        return this;
	}
}
