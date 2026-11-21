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

import fr.wseduc.webutils.security.Sha256;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Clés publiques des appareils enrôlés : lecture du format rendu par le téléphone, identité
 * stable de la clé, et contrôle de robustesse.
 *
 * <p>Le téléphone génère sa paire dans le coffre matériel (Android Keystore, Keychain iOS) et
 * n'en livre que la partie publique, encodée en SPKI/X.509 — c'est ce que rendent aussi bien
 * {@code publicKey.getEncoded()} côté Android que {@code SecKeyCopyExternalRepresentation}
 * enveloppé d'un en-tête côté iOS. Aucune bibliothèque tierce n'est nécessaire pour la relire :
 * {@link KeyFactory} suffit, ce qui évite d'ajouter BouncyCastle au classpath du module.</p>
 */
public final class DevicePublicKeys {

    /** RSASSA-PKCS1-v1_5 sur SHA-256 — ce que signe aujourd'hui l'application mobile. */
    public static final String ALG_RS256 = "RS256";

    /** ECDSA P-256 sur SHA-256 — accepté dès maintenant pour ne pas avoir à migrer plus tard. */
    public static final String ALG_ES256 = "ES256";

    /**
     * Longueur minimale d'un module RSA. 2048 bits est ce que génère l'application ; en deçà,
     * la clé vient d'ailleurs que du coffre matériel et n'a rien à faire ici.
     */
    private static final int MIN_RSA_MODULUS_BITS = 2048;

    /** Taille du corps fini de la courbe P-256, seule courbe acceptée. */
    private static final int P256_FIELD_SIZE = 256;

    private DevicePublicKeys() {
    }

    /** Vrai si l'algorithme est l'un de ceux que l'on sait vérifier. */
    public static boolean isSupportedAlg(final String alg) {
        return ALG_RS256.equals(alg) || ALG_ES256.equals(alg);
    }

    /**
     * Identité de la clé : empreinte SHA-256 de son encodage SPKI, en base64url sans
     * remplissage.
     *
     * <p>Dérivée de la clé elle-même plutôt que tirée au hasard, pour trois raisons : les deux
     * côtés peuvent la calculer sans se la transmettre, elle ne révèle aucun secret, et elle
     * sert d'identifiant de document dans la base — ce qui donne une recherche directe quand
     * une assertion arrive avant que l'on sache de quel compte il s'agit.</p>
     *
     * @throws IllegalArgumentException si l'encodage est illisible
     */
    public static String credentialId(final byte[] spki) {
        if (spki == null || spki.length == 0) {
            throw new IllegalArgumentException("device.key.empty");
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Sha256.hash(spki));
        } catch (Exception e) {
            throw new IllegalArgumentException("device.key.digest.failed", e);
        }
    }

    /**
     * Décode une clé publique transmise en base64 (standard ou url, avec ou sans remplissage)
     * et vérifie qu'elle correspond bien à l'algorithme annoncé.
     *
     * <p>L'algorithme n'est pas déduit de la clé mais imposé par l'appelant, puis confronté à
     * elle : c'est ce contrôle croisé qui interdit d'enregistrer une clé RSA en la déclarant
     * ECDSA, et inversement.</p>
     *
     * @throws IllegalArgumentException si la clé est illisible, trop faible, ou d'un type qui
     *                                  ne correspond pas à {@code alg}
     */
    public static PublicKey parse(final String base64Spki, final String alg) {
        if (base64Spki == null || base64Spki.trim().isEmpty()) {
            throw new IllegalArgumentException("device.key.empty");
        }
        if (!isSupportedAlg(alg)) {
            throw new IllegalArgumentException("device.key.alg.unsupported");
        }
        final byte[] spki = decode(base64Spki);
        final PublicKey key = toPublicKey(spki, alg);
        checkStrength(key, alg);
        return key;
    }

    /**
     * Encodage SPKI canonique de la clé, tel qu'il doit être stocké et haché. On repasse par
     * {@code getEncoded()} plutôt que de conserver les octets reçus : deux encodages
     * différents de la même clé donneraient sinon deux identités distinctes.
     */
    public static byte[] encoded(final PublicKey key) {
        return key.getEncoded();
    }

    /** Base64 standard de l'encodage SPKI, forme de stockage en base. */
    public static String encodeToBase64(final PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Décodage tolérant : les plateformes mobiles renvoient du base64 standard, parfois sans
     * remplissage, et un transport JSON peut l'avoir transformé en base64url. Refuser l'une de
     * ces formes ferait échouer l'enrôlement pour une raison purement cosmétique.
     */
    private static byte[] decode(final String raw) {
        final String cleaned = raw.trim().replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(withPadding(cleaned));
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(withPadding(cleaned));
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException("device.key.malformed", e2);
            }
        }
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

    private static PublicKey toPublicKey(final byte[] spki, final String alg) {
        final String keyAlgorithm = ALG_RS256.equals(alg) ? "RSA" : "EC";
        try {
            return KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            // Une clé EC présentée comme RSA échoue ici : le type annoncé et le contenu réel
            // doivent coïncider.
            throw new IllegalArgumentException("device.key.malformed", e);
        }
    }

    private static void checkStrength(final PublicKey key, final String alg) {
        if (ALG_RS256.equals(alg)) {
            if (!(key instanceof RSAPublicKey)
                    || ((RSAPublicKey) key).getModulus().bitLength() < MIN_RSA_MODULUS_BITS) {
                throw new IllegalArgumentException("device.key.too.weak");
            }
            return;
        }
        if (!(key instanceof ECPublicKey)
                || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != P256_FIELD_SIZE) {
            throw new IllegalArgumentException("device.key.curve.unsupported");
        }
    }

}
