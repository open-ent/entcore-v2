package org.entcore.interoperability;

import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Accès au paquet de référence, servi depuis le classpath de test. */
public final class TestFixtures {

    public static final String PACKAGE_ROOT = "oeip/fixtures/minimal-1.0/";

    private TestFixtures() {}

    public static String read(String pathInPackage) {
        InputStream in = TestFixtures.class.getClassLoader()
                .getResourceAsStream(PACKAGE_ROOT + pathInPackage);
        if (in == null) {
            throw new IllegalStateException("Fixture absente : " + PACKAGE_ROOT + pathInPackage);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    public static JsonObject json(String pathInPackage) {
        return new JsonObject(read(pathInPackage));
    }
}
