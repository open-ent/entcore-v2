package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Ce que la scellement d'un paquet protège réellement.
 *
 * Le manifeste est le document NORMATIF : il déclare la fidélité service par service, les niveaux
 * présents et la présence de personnes mineures. Il a d'abord été exclu du relevé de sommes —
 * par circularité, puisqu'il en épinglait l'empreinte — et un intermédiaire pouvait alors le
 * réécrire sans casser ni l'intégrité ni la signature : un paquet « vérifié » pouvait mentir sur
 * tout ce qu'il promet. Ces tests gardent la correction.
 */
public class SealedManifestTest {

    private static final String ISSUER = "ent.exemple-a.fr";

    private Path sceller(String nom, KeyPair paire) throws Exception {
        Path work = Paths.get("target", "sealed-manifest", nom);
        supprimer(work);
        Path staging = work.resolve("staging");
        Files.createDirectories(staging);

        OeipPackageWriter writer = new OeipPackageWriter(staging);
        writer.putBytes("resources/blog/posts.json",
                "{\"items\":[]}\n".getBytes(StandardCharsets.UTF_8));
        if (paire != null) {
            writer.signedBy(sha -> {
                try {
                    return OeipSignature.sign(sha, ISSUER, "cle-1", paire.getPrivate());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        JsonObject manifest = new JsonObject()
                .put("oeipVersion", "1.0")
                .put("services", new io.vertx.core.json.JsonArray()
                        .add(new JsonObject().put("id", "blog").put("fidelity", "partial")
                                .put("notice", "Les commentaires ne sont pas repris.")));
        return writer.seal(manifest, work.resolve("paquet.oeip"));
    }

    private OeipPackageReader relire(Path zip, String nom) throws IOException {
        Path out = Paths.get("target", "sealed-manifest", nom, "relu");
        supprimer(out);
        return OeipPackageReader.open(zip, out, 1L << 30);
    }

    // ------------------------------------------------------------------ le manifeste est couvert

    @Test
    public void leManifesteEstCouvertParLeReleve() throws Exception {
        Path zip = sceller("couvert", null);
        OeipPackageReader reader = relire(zip, "couvert");

        assertTrue("un paquet intact doit passer", reader.verifyIntegrity().isEmpty());
        String releve = new String(Files.readAllBytes(
                reader.getRoot().resolve("checksums.sha256")), StandardCharsets.UTF_8);
        assertTrue("le document normatif doit figurer au relevé",
                releve.contains("  oeip-manifest.json"));
        // Seule la signature s'en excuse : elle ne peut pas se contenir elle-même.
        assertFalse(releve.contains("  META/signature.json"));
        assertFalse(releve.contains("  checksums.sha256"));
    }

    @Test
    public void lEmpreinteDuReleveNEstPasDeclareeDansLeManifeste() throws Exception {
        OeipPackageReader reader = relire(sceller("non-declaree", null), "non-declaree");
        JsonObject integrity = reader.getManifest().getJsonObject("integrity");

        // Une valeur lue dans ce que l'on vérifie ne prouve rien : elle se calcule.
        assertNull(integrity.getString("checksumsSha256"));
        assertEquals("checksums.sha256", integrity.getString("checksumsFile"));
        assertEquals(64, reader.checksumsSha256().length());
    }

    @Test
    public void uneFideliteReecriteDansLeManifesteEstDenonceeSansAucuneCle() throws Exception {
        Path zip = sceller("fidelite", null);
        OeipPackageReader reader = relire(zip, "fidelite");

        // Un intermédiaire promeut tous les services en « full » et efface la réserve.
        Path manifeste = reader.getRoot().resolve("oeip-manifest.json");
        JsonObject falsifie = new JsonObject(new String(
                Files.readAllBytes(manifeste), StandardCharsets.UTF_8));
        falsifie.getJsonArray("services").getJsonObject(0)
                .put("fidelity", "full").remove("notice");
        Files.write(manifeste, falsifie.encodePrettily().getBytes(StandardCharsets.UTF_8));

        List<String> problemes = reader.verifyIntegrity();
        assertEquals(1, problemes.size());
        assertTrue(problemes.get(0), problemes.get(0).contains("oeip-manifest.json"));
    }

    // ------------------------------------------------------------------ ce que la signature ajoute

    @Test
    public void unAdversaireQuiReecritAussiLeReleveEstArreteParLaSignature() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair paire = g.generateKeyPair();

        Path zip = sceller("adversaire", paire);
        OeipPackageReader reader = relire(zip, "adversaire");
        Map<String, PublicKey> confiance = new LinkedHashMap<String, PublicKey>();
        confiance.put(ISSUER, paire.getPublic());
        assertEquals(OeipSignature.Verdict.TRUSTED, OeipSignature.verify(
                reader.getJson(OeipFormatSignaturePath()), reader.checksumsSha256(), confiance));

        // Il falsifie le manifeste ET recalcule le relevé pour effacer sa trace : l'intégrité
        // devient aveugle. C'est exactement là que la signature prend le relais.
        Path manifeste = reader.getRoot().resolve("oeip-manifest.json");
        JsonObject falsifie = new JsonObject(new String(
                Files.readAllBytes(manifeste), StandardCharsets.UTF_8));
        falsifie.getJsonArray("services").getJsonObject(0).put("fidelity", "full");
        Files.write(manifeste, falsifie.encodePrettily().getBytes(StandardCharsets.UTF_8));
        OeipChecksums.write(reader.getRoot());

        assertTrue("l'intégrité seule ne voit plus rien", reader.verifyIntegrity().isEmpty());
        assertEquals("mais la signature n'ancre plus ce relevé",
                OeipSignature.Verdict.INVALID, OeipSignature.verify(
                        reader.getJson(OeipFormatSignaturePath()),
                        reader.checksumsSha256(), confiance));
    }

    private static String OeipFormatSignaturePath() {
        return org.entcore.interoperability.OeipFormat.SIGNATURE;
    }

    private static void supprimer(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        java.io.File[] children = p.toFile().listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                supprimer(c.toPath());
            }
        }
        Files.deleteIfExists(p);
    }
}
