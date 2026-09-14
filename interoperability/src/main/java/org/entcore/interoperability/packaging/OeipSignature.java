package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Signature détachée d'un paquet, au format JWS (RFC 7515).
 *
 * Elle est <b>distincte de l'intégrité</b>, et cette séparation est le cœur du sujet.
 * L'intégrité — les sommes de contrôle — répond à « ce paquet a-t-il été altéré ? », et se
 * vérifie sans aucune clé, par n'importe qui, y compris dix ans plus tard. La signature répond à
 * « vient-il bien de qui il prétend ? », et suppose de connaître l'émetteur.
 *
 * <p>Confondre les deux est précisément le défaut du format d'archive, dont la signature charge
 * la clé privée et la clé publique depuis le même fichier : elle exige la même paire des deux
 * côtés, ce qui n'a aucun sens entre plateformes distinctes, et rend l'archive invérifiable dès
 * qu'on sort de la plateforme émettrice.
 *
 * <p>D'où la règle appliquée ici : <b>une signature d'émetteur inconnu n'est jamais un motif de
 * rejet.</b> Elle produit un avertissement, et le paquet reste exploitable — l'intégrité, elle,
 * a déjà été établie. Refuser un paquet parce qu'on ne connaît pas son émetteur reviendrait à
 * interdire tout premier échange.
 */
public final class OeipSignature {

    private static final io.vertx.core.logging.Logger LOG =
            io.vertx.core.logging.LoggerFactory.getLogger(OeipSignature.class);

    /** Le seul algorithme admis en 1.0 : disponible partout, et suffisant. */
    public static final String ALGORITHM = "RS256";
    private static final String JAVA_ALGORITHM = "SHA256withRSA";

    /** Résultat d'une vérification : rien n'est fatal, tout est qualifié. */
    public enum Verdict {
        /** Signature valide, émetteur connu. */
        TRUSTED,
        /** Signature présente mais émetteur inconnu : accepté, signalé. */
        UNTRUSTED,
        /** Signature d'un émetteur connu, mais invalide : c'est un problème. */
        INVALID,
        /** Aucune signature : le paquet reste parfaitement recevable. */
        ABSENT
    }

    private OeipSignature() {}

    /**
     * Produit la signature détachée d'un paquet.
     *
     * <p>Ce qui est signé est l'empreinte du relevé de sommes, et rien d'autre : ce relevé couvre
     * tout le contenu — manifeste compris — si bien qu'une signature de quelques octets engage
     * le paquet entier.
     *
     * @param checksumsSha256 empreinte du fichier {@code checksums.sha256}, calculée sur son
     *                        contenu et jamais lue dans le paquet
     */
    public static JsonObject sign(String checksumsSha256, String sourceSystem, String keyId,
                                  PrivateKey key) throws Exception {
        JsonObject header = new JsonObject()
                .put("alg", ALGORITHM)
                .put("typ", "JOSE")
                .put("iss", sourceSystem);
        if (keyId != null && !keyId.isEmpty()) {
            header.put("kid", keyId);
        }
        String protectedHeader = base64Url(header.encode().getBytes(StandardCharsets.UTF_8));
        String signingInput = protectedHeader + "." + checksumsSha256;

        Signature signer = Signature.getInstance(JAVA_ALGORITHM);
        signer.initSign(key);
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));

        return new JsonObject()
                .put("protected", protectedHeader)
                .put("signature", base64Url(signer.sign()))
                // La charge utile est DÉTACHÉE : on rappelle sur quoi la signature porte, sans
                // la dupliquer, pour que la vérification ne dépende d'aucune convention tacite.
                .put("payloadRef", new JsonObject()
                        .put("file", OeipFormat.CHECKSUMS)
                        .put("algorithm", "sha256"));
    }

    /**
     * @param trustedKeys clés publiques des émetteurs connus, par autorité émettrice
     */
    public static Verdict verify(JsonObject signature, String checksumsSha256,
                                 java.util.Map<String, PublicKey> trustedKeys) {
        if (signature == null) {
            return Verdict.ABSENT;
        }
        String protectedHeader = signature.getString("protected");
        String value = signature.getString("signature");
        if (protectedHeader == null || value == null) {
            return Verdict.UNTRUSTED;
        }
        String issuer;
        try {
            issuer = new JsonObject(new String(Base64.getUrlDecoder().decode(protectedHeader),
                    StandardCharsets.UTF_8)).getString("iss");
        } catch (Exception e) {
            return Verdict.UNTRUSTED;
        }
        PublicKey key = issuer == null ? null : trustedKeys.get(issuer);
        if (key == null) {
            // On ne connaît pas cet émetteur : on ne peut ni confirmer ni infirmer. Le dire, et
            // laisser passer — l'intégrité du paquet, elle, est déjà établie.
            return Verdict.UNTRUSTED;
        }
        try {
            Signature verifier = Signature.getInstance(JAVA_ALGORITHM);
            verifier.initVerify(key);
            verifier.update((protectedHeader + "." + checksumsSha256)
                    .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(value))
                    ? Verdict.TRUSTED : Verdict.INVALID;
        } catch (Exception e) {
            return Verdict.INVALID;
        }
    }

    /** Message destiné au rapport d'import, dans la langue de l'exploitant. */
    public static String describe(Verdict verdict, String issuer) {
        switch (verdict) {
            case TRUSTED:
                return "Signature vérifiée : ce paquet vient bien de " + issuer + ".";
            case UNTRUSTED:
                return "Ce paquet est signé par un émetteur que cette plateforme ne connaît pas. "
                        + "Son intégrité est établie, mais son origine n'est pas confirmée.";
            case INVALID:
                return "La signature de ce paquet ne correspond pas à la clé connue de son "
                        + "émetteur. À traiter avec la plus grande prudence.";
            default:
                return "Ce paquet n'est pas signé. Son intégrité reste vérifiable sans clé.";
        }
    }

    public static String issuerOf(JsonObject signature) {
        if (signature == null || signature.getString("protected") == null) {
            return null;
        }
        try {
            return new JsonObject(new String(
                    Base64.getUrlDecoder().decode(signature.getString("protected")),
                    StandardCharsets.UTF_8)).getString("iss");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Clés publiques des émetteurs connus, telles que déclarées en configuration.
     *
     * <p>Chaque entrée vaut {@code {sourceSystem, publicKey}} où {@code publicKey} est le chemin
     * d'un PEM au format X.509 SubjectPublicKeyInfo — la clé <b>publique</b> de l'autre
     * plateforme, celle qu'elle publie. On ne détient jamais la clé privée d'autrui, et une
     * configuration qui l'exigerait rendrait la vérification impossible entre plateformes
     * distinctes : c'est précisément le défaut du format d'archive, dont la clé de vérification
     * est dérivée de la clé de signature, donc du même fichier privé.
     */
    public static java.util.Map<String, PublicKey> loadTrustedKeys(
            io.vertx.core.Vertx vertx, JsonArray trustedIssuers) {
        java.util.Map<String, PublicKey> keys = new java.util.LinkedHashMap<String, PublicKey>();
        if (trustedIssuers == null) {
            return keys;
        }
        for (int i = 0; i < trustedIssuers.size(); i++) {
            Object raw = trustedIssuers.getValue(i);
            if (!(raw instanceof JsonObject)) {
                continue;
            }
            JsonObject issuer = (JsonObject) raw;
            String system = issuer.getString("sourceSystem");
            String path = issuer.getString("publicKey");
            if (system == null || system.trim().isEmpty()
                    || path == null || path.trim().isEmpty()) {
                continue;
            }
            try {
                keys.put(system, readPublicKey(
                        vertx.fileSystem().readFileBlocking(path).toString(
                                StandardCharsets.UTF_8)));
            } catch (Exception e) {
                // Une clé illisible ne doit pas empêcher la plateforme de démarrer : l'émetteur
                // sera simplement traité comme inconnu. Mais le taire laisserait croire qu'il est
                // reconnu — c'est la panne qu'on ne diagnostique jamais.
                keys.remove(system);
                LOG.error("[OEIP] clé publique illisible pour l'émetteur " + system + " (" + path
                        + ") : il sera traité comme inconnu. Un PEM X.509 « BEGIN PUBLIC KEY » "
                        + "est attendu, pas une clé privée.", e);
            }
        }
        return keys;
    }

    /**
     * Lit un PEM X.509 SubjectPublicKeyInfo.
     *
     * <p>Écrit ici plutôt que repris du socle : {@code RSA.loadPublicKey} y dérive en réalité la
     * clé publique d'une clé <i>privée</i> lue au même chemin. Cela convient à une plateforme qui
     * se relit elle-même, jamais à deux plateformes qui s'échangent un paquet.
     */
    public static PublicKey readPublicKey(String pem) throws Exception {
        // Exiger le marqueur, et non se contenter d'un résidu non vide : pointer une clé privée
        // ou un fichier quelconque produirait sinon un « Illegal base64 character » dont
        // l'exploitant ne peut rien tirer. Le refus doit nommer ce qui est attendu.
        if (pem == null || !pem.matches("(?s).*-+\\s*BEGIN\\s+PUBLIC\\s+KEY\\s*-+.*")) {
            throw new IllegalArgumentException("aucun bloc « BEGIN PUBLIC KEY » dans ce fichier : "
                    + "la clé PUBLIQUE de l'émetteur est attendue, au format PEM X.509");
        }
        String base64 = pem
                .replaceAll("-+\\s*BEGIN\\s+PUBLIC\\s+KEY\\s*-+", "")
                .replaceAll("-+\\s*END\\s+PUBLIC\\s+KEY\\s*-+", "")
                .replaceAll("\\s+", "");
        return java.security.KeyFactory.getInstance("RSA").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
