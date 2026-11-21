/*
 * Copyright © "Open Digital Education", 2025
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

package org.entcore.auth;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.entcore.auth.security.DeviceAssertion;
import org.entcore.auth.security.DeviceChallengeStore;
import org.entcore.auth.security.DevicePublicKeys;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Vérification des preuves de possession d'un appareil.
 *
 * <p>Les vecteurs sont produits ici en {@code SHA256withRSA} sur une clé RSA 2048 : c'est
 * exactement ce que signe {@code react-native-biometrics} côté téléphone. Ce test est donc un
 * test d'interopérabilité avec le client, et pas une tautologie où l'on vérifierait sa propre
 * sortie.</p>
 *
 * <p>L'essentiel est fait de cas négatifs : ce qui compte dans une vérification de signature,
 * ce n'est pas qu'elle accepte le jeton honnête, c'est qu'elle refuse tous les autres.</p>
 */
public class DeviceAssertionTest {

    private static final String AUDIENCE = "https://ent.example.org";
    private static final String USER_ID = "a1b2c3d4-0000-0000-0000-000000000001";
    private static final String DEVICE_ID = "d1d2d3d4-0000-0000-0000-000000000002";
    private static final String PURPOSE = "login";

    private KeyPair keyPair;
    private JsonObject credential;
    private FakeChallengeStore challenges;

    @Before
    public void setUp() throws Exception {
        keyPair = rsaKeyPair();
        credential = credentialFor(keyPair, USER_ID, DEVICE_ID);
        challenges = new FakeChallengeStore();
    }

    // --- Cas nominal ------------------------------------------------------------------

    @Test
    public void acceptsAnAssertionSignedByTheEnrolledKey() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final Future<JsonObject> result = verify(assertion(claims(challenge)));

        assertTrue("une assertion honnête doit être acceptée", result.succeeded());
        assertEquals(USER_ID, result.result().getString("sub"));
    }

    @Test
    public void consumesTheChallengeSoItCannotBeReplayed() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final String assertion = assertion(claims(challenge));

        assertTrue(verify(assertion).succeeded());
        // Même assertion, toujours valablement signée et non expirée : seul le défi à usage
        // unique empêche qu'elle serve une seconde fois.
        assertRejected(verify(assertion));
    }

    // --- Confusion d'algorithme -------------------------------------------------------

    @Test
    public void rejectsAnAssertionClaimingAnotherAlgorithm() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject header = header().put("alg", "HS256");
        assertRejected(verify(assertion(header, claims(challenge))));
    }

    @Test
    public void rejectsAnUnsignedAssertion() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject header = header().put("alg", "none");
        assertRejected(verify(assertion(header, claims(challenge))));
    }

    @Test
    public void rejectsAnAssertionWithAnUnexpectedType() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject header = header().put("typ", "JWT");
        assertRejected(verify(assertion(header, claims(challenge))));
    }

    // --- Mauvaise clé -----------------------------------------------------------------

    @Test
    public void rejectsAnAssertionSignedByAnotherKey() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final KeyPair intruder = rsaKeyPair();
        assertRejected(verify(assertion(header(), claims(challenge), intruder.getPrivate())));
    }

    @Test
    public void rejectsAnAssertionNamingAnotherKeyId() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject header = header().put("kid", "une-autre-cle");
        assertRejected(verify(assertion(header, claims(challenge))));
    }

    // --- Mauvais contexte -------------------------------------------------------------

    @Test
    public void rejectsAnAssertionIssuedForAnotherInstance() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject claims = claims(challenge).put("aud", "https://autre-ent.example.org");
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionProducedForAnotherPurpose() throws Exception {
        // Le défi existe bien, mais il a été émis pour l'enrôlement.
        final String challenge = challenges.put("enrol", USER_ID, credential.getString("_id"));
        final JsonObject claims = claims(challenge).put("pur", "enrol");
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionClaimingAnotherSubject() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject claims = claims(challenge).put("sub", "quelqu-un-d-autre");
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionClaimingAnotherDevice() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject claims = claims(challenge).put("did", "un-autre-appareil");
        assertRejected(verify(assertion(claims)));
    }

    // --- Horodatages ------------------------------------------------------------------

    @Test
    public void rejectsAnExpiredAssertion() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final long longAgo = nowSeconds() - 3600;
        final JsonObject claims = claims(challenge).put("iat", longAgo).put("exp", longAgo + 60);
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionIssuedInTheFuture() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final long later = nowSeconds() + 3600;
        final JsonObject claims = claims(challenge).put("iat", later).put("exp", later + 60);
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionWithoutExpiry() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final JsonObject claims = claims(challenge);
        claims.remove("exp");
        assertRejected(verify(assertion(claims)));
    }

    @Test
    public void rejectsAnAssertionGivingItselfTooLongALifetime() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, credential.getString("_id"));
        final long now = nowSeconds();
        final JsonObject claims = claims(challenge).put("iat", now).put("exp", now + 86400);
        assertRejected(verify(assertion(claims)));
    }

    // --- Défis ------------------------------------------------------------------------

    @Test
    public void rejectsAnAssertionWithAnUnknownChallenge() throws Exception {
        assertRejected(verify(assertion(claims("un-defi-jamais-emis"))));
    }

    @Test
    public void rejectsAChallengeIssuedForAnotherKey() throws Exception {
        final String challenge = challenges.put(PURPOSE, USER_ID, "la-cle-de-quelqu-un-d-autre");
        assertRejected(verify(assertion(claims(challenge))));
    }

    @Test
    public void rejectsAChallengeIssuedForAnotherUser() throws Exception {
        final String challenge = challenges.put(PURPOSE, "un-autre-compte", credential.getString("_id"));
        assertRejected(verify(assertion(claims(challenge))));
    }

    @Test
    public void rejectsAMalformedAssertion() {
        assertRejected(DeviceAssertion.verify("pas-un-jws", credential, PURPOSE, AUDIENCE, challenges));
        assertRejected(DeviceAssertion.verify("a.b", credential, PURPOSE, AUDIENCE, challenges));
        assertRejected(DeviceAssertion.verify("@@@.@@@.@@@", credential, PURPOSE, AUDIENCE, challenges));
    }

    // --- Identité et robustesse des clés ----------------------------------------------

    @Test
    public void credentialIdIsStableAndTiedToTheKey() throws Exception {
        final String first = DevicePublicKeys.credentialId(keyPair.getPublic().getEncoded());
        final String second = DevicePublicKeys.credentialId(keyPair.getPublic().getEncoded());
        assertEquals("la même clé doit toujours donner la même identité", first, second);
        assertNotEquals("deux clés distinctes ne peuvent pas partager une identité",
                first, DevicePublicKeys.credentialId(rsaKeyPair().getPublic().getEncoded()));
    }

    @Test
    public void readsAKeyWhateverTheBase64Flavour() throws Exception {
        final String standard = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        final String urlSafe = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(keyPair.getPublic().getEncoded());
        assertEquals(DevicePublicKeys.parse(standard, DevicePublicKeys.ALG_RS256),
                DevicePublicKeys.parse(urlSafe, DevicePublicKeys.ALG_RS256));
    }

    @Test
    public void rejectsAKeyWhoseTypeContradictsItsAlgorithm() throws Exception {
        final KeyPair ec = ecKeyPair();
        final String encoded = Base64.getEncoder().encodeToString(ec.getPublic().getEncoded());
        assertIllegal(() -> DevicePublicKeys.parse(encoded, DevicePublicKeys.ALG_RS256));
    }

    @Test
    public void rejectsATooWeakRsaKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        final String encoded = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        assertIllegal(() -> DevicePublicKeys.parse(encoded, DevicePublicKeys.ALG_RS256));
    }

    @Test
    public void acceptsAP256KeyForEcdsa() throws Exception {
        final String encoded = Base64.getEncoder().encodeToString(ecKeyPair().getPublic().getEncoded());
        // ES256 est accepté dès maintenant pour que le passage à P-256 ne coûte rien côté socle.
        assertTrue(DevicePublicKeys.parse(encoded, DevicePublicKeys.ALG_ES256) != null);
    }

    // --- Outillage --------------------------------------------------------------------

    private Future<JsonObject> verify(final String assertion) {
        return DeviceAssertion.verify(assertion, credential, PURPOSE, AUDIENCE, challenges);
    }

    private static void assertRejected(final Future<JsonObject> result) {
        assertTrue("l'assertion aurait dû être refusée", result.failed());
        // Une seule et même cause rendue à l'appelant : rien ne doit permettre de distinguer
        // « clé inconnue » de « signature invalide ».
        assertEquals(DeviceAssertion.REJECTED, result.cause().getMessage());
    }

    private static void assertIllegal(final Runnable runnable) {
        try {
            runnable.run();
            assertFalse("cette clé aurait dû être refusée", true);
        } catch (IllegalArgumentException expected) {
            // attendu
        }
    }

    private JsonObject header() {
        return new JsonObject()
                .put("typ", DeviceAssertion.TYPE)
                .put("alg", DevicePublicKeys.ALG_RS256)
                .put("kid", credential.getString("_id"));
    }

    private JsonObject claims(final String challenge) {
        final long now = nowSeconds();
        return new JsonObject()
                .put("iss", credential.getString("_id"))
                .put("sub", USER_ID)
                .put("aud", AUDIENCE)
                .put("jti", challenge)
                .put("iat", now)
                .put("exp", now + 120)
                .put("did", DEVICE_ID)
                .put("pur", PURPOSE);
    }

    private String assertion(final JsonObject claims) throws Exception {
        return assertion(header(), claims, keyPair.getPrivate());
    }

    private String assertion(final JsonObject header, final JsonObject claims) throws Exception {
        return assertion(header, claims, keyPair.getPrivate());
    }

    /**
     * Fabrique le JWS comme le fait l'application : elle signe les octets de
     * {@code header.claims} et recolle la signature derrière.
     */
    private static String assertion(final JsonObject header, final JsonObject claims,
                                    final PrivateKey privateKey) throws Exception {
        final String signingInput = b64(header.encode()) + "." + b64(claims.encode());
        final Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String b64(final String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static JsonObject credentialFor(final KeyPair keyPair, final String userId, final String deviceId) {
        final byte[] spki = keyPair.getPublic().getEncoded();
        return new JsonObject()
                .put("_id", DevicePublicKeys.credentialId(spki))
                .put("alg", DevicePublicKeys.ALG_RS256)
                .put("publicKey", Base64.getEncoder().encodeToString(spki))
                .put("userId", userId)
                .put("deviceId", deviceId);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ecKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Magasin de défis en mémoire. Reproduit la seule propriété qui compte : un défi ne se
     * consomme qu'une fois, et sa suppression est ce qui le prouve.
     */
    private static class FakeChallengeStore extends DeviceChallengeStore {

        private final Map<String, JsonObject> issued = new HashMap<>();

        String put(final String purpose, final String userId, final String credentialId) {
            final String challenge = "defi-" + issued.size();
            issued.put(challenge, new JsonObject()
                    .put("_id", challenge)
                    .put("purpose", purpose)
                    .put("userId", userId)
                    .put("credentialId", credentialId)
                    .put("expiresAt", System.currentTimeMillis() + 120_000L));
            return challenge;
        }

        @Override
        public Future<JsonObject> consume(final String challenge, final String purpose) {
            if (challenge == null) {
                return Future.failedFuture("device.challenge.invalid");
            }
            final JsonObject found = issued.get(challenge);
            if (found == null || !found.getString("purpose").equals(purpose)) {
                return Future.failedFuture("device.challenge.invalid");
            }
            issued.remove(challenge);
            return Future.succeededFuture(found);
        }
    }

}
