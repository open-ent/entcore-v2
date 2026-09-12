package org.entcore.interoperability.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.uri.URIFetcher;
import io.vertx.core.json.JsonObject;
import org.entcore.interoperability.OeipFormat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Charge le lot de schémas OEIP embarqué dans le module et en dérive une fabrique de
 * validateurs qui résout les références croisées SANS accès réseau.
 *
 * Les schémas se référencent par nom de fichier relatif à leur $id (https://open-ent.fr/oeip/…).
 * Sans interception, la bibliothèque tenterait de les télécharger : le fetcher ci-dessous les
 * sert depuis le classpath. C'est ce qui rend un paquet validable hors ligne, et c'est aussi
 * pourquoi le lot est recopié dans chaque paquet produit.
 */
public class OeipSchemaRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> rawByFileName = new LinkedHashMap<>();
    private final JsonSchemaFactory factory;
    private final String bundleSha256;

    public OeipSchemaRegistry() {
        for (String name : OeipFormat.SCHEMA_FILES) {
            String path = OeipFormat.SCHEMA_CLASSPATH_DIR + "/" + name;
            String content = readClasspath(path);
            if (content == null) {
                throw new IllegalStateException("Schéma OEIP introuvable dans le classpath : " + path);
            }
            rawByFileName.put(name, content);
        }

        URIFetcher classpathFetcher = new URIFetcher() {
            @Override
            public InputStream fetch(URI uri) throws IOException {
                String name = uri.getPath();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                String content = rawByFileName.get(name);
                if (content == null) {
                    throw new IOException("Schéma OEIP inconnu : " + uri);
                }
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };

        this.factory = JsonSchemaFactory
                .builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
                .uriFetcher(classpathFetcher, "https", "http")
                .build();

        this.bundleSha256 = computeBundleSha256();
    }

    /**
     * Empreinte du lot : sha256 du listing « <sha256>  <chemin dans le paquet> » trié.
     * Le manifeste l'épingle ; une divergence à l'import vaut AVERTISSEMENT et non rejet,
     * car un ENT tiers peut légitimement étendre le format.
     */
    private String computeBundleSha256() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : rawByFileName.entrySet()) {
            String sha = sha256(e.getValue().getBytes(StandardCharsets.UTF_8));
            lines.add(sha + "  " + OeipFormat.SCHEMA_PACKAGE_DIR + "/" + e.getKey());
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public String getBundleSha256() {
        return bundleSha256;
    }

    public Map<String, String> getRawSchemas() {
        return Collections.unmodifiableMap(rawByFileName);
    }

    public String getRawSchema(String fileName) {
        return rawByFileName.get(fileName);
    }

    public JsonObject index() {
        JsonObject json = new JsonObject()
                .put("oeipVersion", OeipFormat.VERSION)
                .put("baseUri", OeipFormat.SCHEMA_BASE_URI)
                .put("bundleSha256", bundleSha256);
        io.vertx.core.json.JsonArray names = new io.vertx.core.json.JsonArray();
        for (String n : rawByFileName.keySet()) {
            names.add(n);
        }
        return json.put("schemas", names);
    }

    JsonSchema schema(String fileName) {
        String raw = rawByFileName.get(fileName);
        if (raw == null) {
            throw new IllegalArgumentException("Schéma OEIP inconnu : " + fileName);
        }
        // Le schéma est enregistré SOUS SON URI de récupération : c'est ce qui permet aux
        // références relatives entre schémas (« common-1.0.schema.json#/definitions/… ») d'être
        // résolues, et donc interceptées par le fetcher classpath.
        try {
            JsonNode node = MAPPER.readTree(raw);
            return factory.getSchema(URI.create(OeipFormat.SCHEMA_BASE_URI + fileName), node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Schéma OEIP illisible : " + fileName, e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static String readClasspath(String path) {
        ClassLoader cl = OeipSchemaRegistry.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(path);
        if (in == null) {
            in = OeipSchemaRegistry.class.getResourceAsStream("/" + path);
        }
        if (in == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
