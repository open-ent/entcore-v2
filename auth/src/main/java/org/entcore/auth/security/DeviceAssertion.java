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

package org.entcore.auth.security;

import fr.wseduc.webutils.security.JWT;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;

import static fr.wseduc.webutils.Utils.isEmpty;
import static fr.wseduc.webutils.Utils.isNotEmpty;

/**
 * Preuve de possession d'un appareil : un jeton signé (JWS) par la clé privée que le téléphone
 * garde dans son coffre matériel, et que l'OS ne laisse utiliser qu'après déverrouillage
 * biométrique.
 *
 * <h3>Usage</h3>
 * <pre>
 * { "typ":"ent-device+jwt", "alg":"RS256", "kid":"&lt;credentialId&gt;" }
 * { "iss":…, "sub":&lt;userId&gt;, "aud":&lt;origine&gt;, "jti":&lt;défi&gt;, "iat":…, "exp":…,
 *   "did":&lt;deviceId&gt;, "pur":"enrol|login|mfa|approve", "ctx":… }
 * </pre>
 *
 * <h3>Pourquoi l'algorithme est épinglé avant toute chose</h3>
 * <p>{@link JWT#verifyAndGet(String, PublicKey)} lit l'algorithme <b>dans le jeton présenté</b>
 * et ne vérifie que la signature — ni échéance, ni destinataire, ni rejeu. Laisser le jeton
 * choisir son propre algorithme est le défaut classique des implémentations JWT. On le refuse
 * donc avant d'appeler quoi que ce soit, en le confrontant à l'algorithme enregistré avec la
 * clé au moment de l'enrôlement.</p>
 *
 * <p>Toutes les causes de rejet renvoient le même échec, {@value #REJECTED} : distinguer
 * « clé inconnue » de « signature invalide » ferait de cette route un oracle.</p>
 */
public final class DeviceAssertion {

    private static final Logger log = LoggerFactory.getLogger(DeviceAssertion.class);

    /** Seul message d'échec rendu à l'appelant, quelle que soit la cause réelle. */
    public static final String REJECTED = "device.assertion.rejected";

    /** Type attendu, pour qu'une assertion d'appareil ne soit pas confondue avec un autre JWS. */
    public static final String TYPE = "ent-device+jwt";

    /**
     * Tolérance d'horloge. Les appareils scolaires et familiaux dérivent ; refuser une seconde
     * d'avance ferait échouer des connexions légitimes sans rien apporter, le rejeu étant déjà
     * interdit par le défi à usage unique.
     */
    private static final long CLOCK_SKEW_MS = 60_000L;

    /**
     * Durée de validité maximale qu'une assertion peut se donner. Le défi expire de toute
     * façon plus tôt ; ce plafond évite seulement qu'une assertion prétende valoir des mois,
     * ce qui n'aurait aucune raison d'être.
     */
    private static final long MAX_LIFETIME_MS = 600_000L;

    private DeviceAssertion() {
    }

    /**
     * Vérifie une assertion de bout en bout et consomme son défi.
     *
     * <p>L'ordre des contrôles est délibéré : tout ce qui peut être tranché sans la base l'est
     * d'abord, et le défi n'est consommé qu'une fois la signature établie — sans quoi une
     * assertion mal signée suffirait à épuiser le défi d'autrui.</p>
     *
     * @param assertion  le JWS présenté par l'appareil
     * @param credential description de la clé attendue : {@code _id} (credentialId),
     *                   {@code alg}, {@code publicKey} (SPKI base64), et facultativement
     *                   {@code userId} et {@code deviceId} à confronter aux revendications
     * @param purpose    usage attendu ; une assertion produite pour un autre usage est rejetée
     * @param audience   origine de l'instance ({@code https://hôte}), confrontée à {@code aud}
     * @param challenges magasin des défis, consulté en dernier
     * @return les revendications vérifiées
     */
    public static Future<JsonObject> verify(final String assertion, final JsonObject credential,
                                            final String purpose, final String audience,
                                            final DeviceChallengeStore challenges) {
        if (isEmpty(assertion) || credential == null || isEmpty(purpose) || challenges == null) {
            return reject("missing argument");
        }
        final String[] parts = assertion.split("\\.");
        if (parts.length != 3) {
            return reject("malformed assertion");
        }

        final JsonObject header;
        final JsonObject claims;
        try {
            header = new JsonObject(decode(parts[0]));
            claims = new JsonObject(decode(parts[1]));
        } catch (Exception e) {
            return reject("unreadable assertion");
        }

        // 1. Algorithme épinglé sur celui enregistré avec la clé, avant tout appel de vérification.
        final String expectedAlg = credential.getString("alg");
        if (!DevicePublicKeys.isSupportedAlg(expectedAlg) || !expectedAlg.equals(header.getString("alg"))) {
            return reject("algorithm mismatch");
        }
        if (!TYPE.equals(header.getString("typ"))) {
            return reject("unexpected type");
        }

        // 2. Identité de la clé. Absente à l'enrôlement, où la clé voyage dans le corps de la
        //    requête et n'a pas encore d'identifiant connu du téléphone.
        final String credentialId = credential.getString("_id");
        final String kid = header.getString("kid");
        if (isNotEmpty(kid) && isNotEmpty(credentialId) && !kid.equals(credentialId)) {
            return reject("key id mismatch");
        }

        // 3. Signature.
        final PublicKey publicKey;
        try {
            publicKey = DevicePublicKeys.parse(credential.getString("publicKey"), expectedAlg);
        } catch (IllegalArgumentException e) {
            return reject("unusable stored key: " + e.getMessage());
        }
        if (JWT.verifyAndGet(assertion, publicKey) == null) {
            return reject("bad signature");
        }

        // 4. Destinataire : sans ce contrôle, une assertion obtenue sur une instance serait
        //    rejouable sur une autre où le même téléphone est enrôlé.
        if (isNotEmpty(audience) && !audience.equals(claims.getString("aud"))) {
            return reject("wrong audience");
        }

        // 5. Usage.
        if (!purpose.equals(claims.getString("pur"))) {
            return reject("wrong purpose");
        }

        // 6. Horodatages.
        final String timeError = checkTimes(claims);
        if (timeError != null) {
            return reject(timeError);
        }

        // 7. Identités revendiquées, quand on sait déjà à quoi les confronter.
        final String expectedSub = credential.getString("userId");
        if (isNotEmpty(expectedSub) && !expectedSub.equals(claims.getString("sub"))) {
            return reject("subject mismatch");
        }
        final String expectedDeviceId = credential.getString("deviceId");
        if (isNotEmpty(expectedDeviceId) && !expectedDeviceId.equals(claims.getString("did"))) {
            return reject("device mismatch");
        }

        // 8. Défi, consommé en dernier et une seule fois.
        return challenges.consume(claims.getString("jti"), purpose)
                .compose(challenge -> {
                    final String boundTo = challenge.getString("credentialId");
                    if (isNotEmpty(boundTo) && isNotEmpty(credentialId) && !boundTo.equals(credentialId)) {
                        return DeviceAssertion.<JsonObject>reject("challenge issued for another key");
                    }
                    final String boundUser = challenge.getString("userId");
                    if (isNotEmpty(boundUser) && !boundUser.equals(claims.getString("sub"))) {
                        return DeviceAssertion.<JsonObject>reject("challenge issued for another user");
                    }
                    return Future.succeededFuture(claims);
                })
                .recover(t -> reject("challenge rejected: " + t.getMessage()));
    }

    /**
     * Échéance et émission. {@code exp} est obligatoire : une assertion sans échéance resterait
     * valable indéfiniment pour qui la capterait avant sa première présentation.
     *
     * @return la raison du rejet, ou {@code null} si les horodatages conviennent
     */
    private static String checkTimes(final JsonObject claims) {
        final Long iat = asEpochMillis(claims.getValue("iat"));
        final Long exp = asEpochMillis(claims.getValue("exp"));
        if (exp == null) {
            return "missing expiry";
        }
        final long now = System.currentTimeMillis();
        if (exp + CLOCK_SKEW_MS < now) {
            return "expired";
        }
        if (iat != null) {
            if (iat - CLOCK_SKEW_MS > now) {
                return "issued in the future";
            }
            if (exp - iat > MAX_LIFETIME_MS) {
                return "lifetime too long";
            }
        }
        return null;
    }

    /** Les horodatages JWT sont en secondes ; on les ramène en millisecondes. */
    private static Long asEpochMillis(final Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue() * 1000L;
        }
        return null;
    }

    private static String decode(final String segment) {
        return new String(Base64.getUrlDecoder().decode(withPadding(segment)), StandardCharsets.UTF_8);
    }

    private static String withPadding(final String s) {
        final int remainder = s.length() % 4;
        if (remainder == 0) {
            return s;
        }
        final StringBuilder padded = new StringBuilder(s);
        for (int i = remainder; i < 4; i++) {
            padded.append('=');
        }
        return padded.toString();
    }

    /** Journalise la cause réelle et ne rend qu'un échec indifférencié. */
    private static <T> Future<T> reject(final String reason) {
        log.debug("Device assertion rejected: " + reason);
        return Future.failedFuture(REJECTED);
    }

}
