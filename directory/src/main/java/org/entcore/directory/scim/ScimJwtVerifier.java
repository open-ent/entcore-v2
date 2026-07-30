/*
 * Vérification de signature des Security Event Tokens (JWT RS256/384/512) émis par l'IAM,
 * via le JWKS publié par l'IAM. Java pur (java.security), sans dépendance JWT externe.
 *
 * NB : non testé en environnement local (le chemin HTTP exige un entcore lancé) — voir cadrage-phase3.md.
 */
package org.entcore.directory.scim;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class ScimJwtVerifier {

    private static final Map<String, String> ALG = new HashMap<>();
    static {
        ALG.put("RS256", "SHA256withRSA");
        ALG.put("RS384", "SHA384withRSA");
        ALG.put("RS512", "SHA512withRSA");
    }

    private final Vertx vertx;
    private final String jwksUrl;
    private volatile Map<String, PublicKey> keyCache;

    public ScimJwtVerifier(Vertx vertx, String jwksUrl) {
        this.vertx = vertx;
        this.jwksUrl = jwksUrl;
    }

    /** Vérifie la signature du JWT et renvoie le payload décodé, ou un échec si la signature est invalide. */
    public Future<JsonObject> verifyAndDecode(String jwt) {
        final String[] parts = jwt.split("\\.");
        if (parts.length != 3) return Future.failedFuture("scim.jwt.malformed");
        final JsonObject header;
        final JsonObject payload;
        try {
            header = new JsonObject(new String(b64url(parts[0]), StandardCharsets.UTF_8));
            payload = new JsonObject(new String(b64url(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Future.failedFuture("scim.jwt.decode.error");
        }
        final String javaAlg = ALG.get(header.getString("alg", "").toUpperCase());
        if (javaAlg == null) return Future.failedFuture("scim.jwt.alg.unsupported:" + header.getString("alg"));
        final String kid = header.getString("kid");
        final byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        final byte[] signature;
        try {
            signature = b64url(parts[2]);
        } catch (Exception e) {
            return Future.failedFuture("scim.jwt.signature.decode.error");
        }
        return keys().compose(map -> {
            PublicKey key = kid != null ? map.get(kid) : null;
            if (key == null && !map.isEmpty()) key = map.values().iterator().next();
            if (key == null) return Future.failedFuture("scim.jwks.no.key");
            try {
                final Signature verifier = Signature.getInstance(javaAlg);
                verifier.initVerify(key);
                verifier.update(signingInput);
                return verifier.verify(signature)
                        ? Future.succeededFuture(payload)
                        : Future.failedFuture("scim.jwt.signature.invalid");
            } catch (Exception e) {
                return Future.failedFuture("scim.jwt.verify.error:" + e.getMessage());
            }
        });
    }

    /** Récupère (et met en cache) les clés publiques du JWKS. Lecture réseau déportée hors event-loop. */
    private Future<Map<String, PublicKey>> keys() {
        final Map<String, PublicKey> cached = keyCache;
        if (cached != null) return Future.succeededFuture(cached);
        return vertx.executeBlocking(promise -> {
            try (InputStream in = new URL(jwksUrl).openStream()) {
                final byte[] bytes = in.readAllBytes();
                final JsonArray jwks = new JsonObject(new String(bytes, StandardCharsets.UTF_8)).getJsonArray("keys");
                final Map<String, PublicKey> map = new HashMap<>();
                final KeyFactory kf = KeyFactory.getInstance("RSA");
                for (int i = 0; i < jwks.size(); i++) {
                    final JsonObject jwk = jwks.getJsonObject(i);
                    final BigInteger n = new BigInteger(1, b64url(jwk.getString("n")));
                    final BigInteger e = new BigInteger(1, b64url(jwk.getString("e")));
                    map.put(jwk.getString("kid"), kf.generatePublic(new RSAPublicKeySpec(n, e)));
                }
                keyCache = map;
                promise.complete(map);
            } catch (Exception ex) {
                promise.fail(ex);
            }
        });
    }

    private static byte[] b64url(String s) {
        final int pad = (4 - s.length() % 4) % 4;
        final StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) sb.append('=');
        return Base64.getUrlDecoder().decode(sb.toString());
    }
}
