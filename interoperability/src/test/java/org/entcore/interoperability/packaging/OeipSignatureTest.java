package org.entcore.interoperability.packaging;

import io.vertx.core.json.JsonObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * La signature détachée.
 *
 * L'assertion la plus importante n'est pas qu'une signature valide soit reconnue, mais qu'une
 * signature d'émetteur INCONNU n'empêche pas l'échange. Refuser un paquet parce qu'on ne connaît
 * pas son émetteur interdirait tout premier échange — et l'intégrité, elle, a déjà été établie
 * sans aucune clé.
 */
public class OeipSignatureTest {

    private static final String SHA = "a".repeat(64);
    private static final String ISSUER = "ent.exemple-a.fr";
    private static final String AUTRE = "ent.exemple-b.fr";

    private static KeyPair paire;
    private static KeyPair autrePaire;

    @BeforeClass
    public static void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        paire = g.generateKeyPair();
        autrePaire = g.generateKeyPair();
    }

    private static Map<String, PublicKey> confiance(String system, PublicKey key) {
        Map<String, PublicKey> m = new LinkedHashMap<String, PublicKey>();
        if (system != null) {
            m.put(system, key);
        }
        return m;
    }

    // ------------------------------------------------------------------ la règle cardinale

    @Test
    public void unEmetteurInconnuNEmpechePasLEchange() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        // La plateforme d'arrivée ne connaît personne : premier échange.
        OeipSignature.Verdict v = OeipSignature.verify(sig, SHA, confiance(null, null));

        assertEquals(OeipSignature.Verdict.UNTRUSTED, v);
        assertTrue(OeipSignature.describe(v, ISSUER).contains("intégrité est établie"));
    }

    @Test
    public void unPaquetNonSigneResteRecevable() {
        OeipSignature.Verdict v = OeipSignature.verify(null, SHA, confiance(ISSUER, paire.getPublic()));
        assertEquals(OeipSignature.Verdict.ABSENT, v);
        assertTrue(OeipSignature.describe(v, null).contains("vérifiable sans clé"));
    }

    // ------------------------------------------------------------------ ce qui est reconnu

    @Test
    public void uneSignatureValideDUnEmetteurConnuEstReconnue() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        assertEquals(OeipSignature.Verdict.TRUSTED,
                OeipSignature.verify(sig, SHA, confiance(ISSUER, paire.getPublic())));
        assertEquals(ISSUER, OeipSignature.issuerOf(sig));
    }

    @Test
    public void laSignatureEstDetacheeEtNeDupliquePasSonContenu() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());

        assertNull("la charge utile n'est pas incluse", sig.getString("payload"));
        assertEquals("checksums.sha256", sig.getJsonObject("payloadRef").getString("file"));
        JsonObject header = new JsonObject(new String(Base64.getUrlDecoder()
                .decode(sig.getString("protected")), StandardCharsets.UTF_8));
        assertEquals("RS256", header.getString("alg"));
        assertEquals(ISSUER, header.getString("iss"));
        assertEquals("cle-1", header.getString("kid"));
    }

    // ------------------------------------------------------------------ ce qui doit alerter

    @Test
    public void uneSignatureAlteréeDUnEmetteurConnuEstUnProbleme() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        // Le paquet a changé après signature : l'intégrité l'aurait déjà dit, mais la signature
        // doit le confirmer plutôt que de passer.
        OeipSignature.Verdict v = OeipSignature.verify(sig, "b".repeat(64),
                confiance(ISSUER, paire.getPublic()));

        assertEquals(OeipSignature.Verdict.INVALID, v);
        assertTrue(OeipSignature.describe(v, ISSUER).contains("prudence"));
    }

    @Test
    public void uneSignatureFaiteAvecUneAutreCleEstRejetee() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", autrePaire.getPrivate());
        assertEquals("l'émetteur est connu, mais la clé ne correspond pas",
                OeipSignature.Verdict.INVALID,
                OeipSignature.verify(sig, SHA, confiance(ISSUER, paire.getPublic())));
    }

    @Test
    public void unEmetteurUsurpeNEstPasReconnu() throws Exception {
        // Quelqu'un signe en se présentant comme une plateforme connue, avec sa propre clé.
        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", autrePaire.getPrivate());
        assertEquals(OeipSignature.Verdict.INVALID,
                OeipSignature.verify(sig, SHA, confiance(ISSUER, paire.getPublic())));
    }

    // ------------------------------------------------------------------ robustesse

    @Test
    public void uneSignatureMalFormeeNeFaitPasEchouerLaVerification() {
        assertEquals(OeipSignature.Verdict.UNTRUSTED, OeipSignature.verify(
                new JsonObject().put("protected", "pas-du-base64!!"), SHA,
                confiance(ISSUER, paire.getPublic())));
        assertEquals(OeipSignature.Verdict.UNTRUSTED, OeipSignature.verify(
                new JsonObject(), SHA, confiance(ISSUER, paire.getPublic())));
        assertNull(OeipSignature.issuerOf(new JsonObject()));
    }

    @Test
    public void uneSignatureSansEmetteurEstTraiteeCommeInconnue() throws Exception {
        JsonObject sig = OeipSignature.sign(SHA, null, null, paire.getPrivate());
        assertEquals(OeipSignature.Verdict.UNTRUSTED,
                OeipSignature.verify(sig, SHA, confiance(ISSUER, paire.getPublic())));
    }

    @Test
    public void deuxSignaturesDuMemeContenuSontVerifiables() throws Exception {
        // On ne teste pas l'égalité binaire : RSA-PKCS#1 v1.5 est déterministe, mais s'appuyer
        // là-dessus rendrait le test faux le jour où l'on passerait à un schéma probabiliste.
        JsonObject a = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        JsonObject b = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        Map<String, PublicKey> t = confiance(ISSUER, paire.getPublic());
        assertEquals(OeipSignature.Verdict.TRUSTED, OeipSignature.verify(a, SHA, t));
        assertEquals(OeipSignature.Verdict.TRUSTED, OeipSignature.verify(b, SHA, t));
    }

    // ------------------------------------------------------------------ la clé de l'autre

    /**
     * On ne détient jamais la clé privée d'une autre plateforme.
     *
     * Ce test existe parce que le socle en fait l'inverse : {@code RSA.loadPublicKey} dérive la
     * clé publique d'une clé PRIVÉE lue au même chemin. Configurer un émetteur de confiance
     * exigerait alors son secret — donc, en pratique, la vérification n'était possible qu'entre
     * une plateforme et elle-même. C'est le défaut d'{@code archive.signature}, et le reproduire
     * viderait la signature de son sens.
     */
    @Test
    public void unEmetteurDeConfianceSeDeclareParSaClePublique() throws Exception {
        String pem = pem("PUBLIC KEY", paire.getPublic().getEncoded());
        PublicKey relue = OeipSignature.readPublicKey(pem);

        JsonObject sig = OeipSignature.sign(SHA, ISSUER, "cle-1", paire.getPrivate());
        assertEquals("une clé publique publiée suffit à vérifier",
                OeipSignature.Verdict.TRUSTED,
                OeipSignature.verify(sig, SHA, confiance(ISSUER, relue)));
    }

    @Test
    public void uneClePriveePresenteeCommeCelleDUnTiersEstRefusee() throws Exception {
        // Le refus est essentiel : accepter ce fichier reviendrait à normaliser une configuration
        // qui réclame le secret d'autrui.
        try {
            OeipSignature.readPublicKey(pem("PRIVATE KEY", paire.getPrivate().getEncoded()));
            fail("une clé privée n'est pas une clé publique");
        } catch (Exception e) {
            // Et le message doit dire à l'exploitant quel fichier fournir.
            assertTrue(e.getMessage().contains("clé PUBLIQUE"));
        }
    }

    @Test
    public void unFichierSansBlocDeCleEstRefuseExplicitement() {
        try {
            OeipSignature.readPublicKey("ceci n'est pas une clé\n");
            fail("un fichier quelconque ne doit pas produire de clé");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("BEGIN PUBLIC KEY"));
        }
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
