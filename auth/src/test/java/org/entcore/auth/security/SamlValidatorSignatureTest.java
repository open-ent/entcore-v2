package org.entcore.auth.security;

import org.joda.time.DateTime;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opensaml.Configuration;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SAMLObjectBuilder;
import org.opensaml.common.SAMLVersion;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.EncryptedAssertion;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.Response;
import org.opensaml.saml2.core.Subject;
import org.opensaml.saml2.encryption.Encrypter;
import org.opensaml.xml.encryption.EncryptionConstants;
import org.opensaml.xml.encryption.EncryptionParameters;
import org.opensaml.xml.encryption.KeyEncryptionParameters;
import org.opensaml.xml.security.SecurityHelper;
import org.opensaml.xml.security.credential.Credential;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.SignatureConstants;
import org.opensaml.xml.signature.Signer;
import org.opensaml.xml.util.Base64;
import org.opensaml.xml.util.XMLHelper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Vérification de signature des réponses SAML reçues d'un IdP.
 *
 * Le cas « emballage » reproduit l'attaque corrigée : une réponse non signée portant une
 * assertion en clair légitimement signée par l'IdP et une assertion chiffrée forgée, que
 * n'importe qui peut chiffrer avec la clé publique du SP.
 */
public class SamlValidatorSignatureTest {

    private static final String IDP = "urn:test:idp";

    private static SamlValidator validator;
    private static Credential idpCredential;
    private static Credential spEncryptionCredential;
    private static KeyPair forgerKeys;

    @BeforeClass
    public static void setUp() throws Exception {
        DefaultBootstrap.bootstrap();
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        // Certificat et clé de l'IdP de test : fixture openssl (src/test/resources/saml).
        final X509Certificate idpCertificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(SamlValidatorSignatureTest.class.getResourceAsStream("/saml/idp-cert.der"));
        final PrivateKey idpKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                readAll(SamlValidatorSignatureTest.class.getResourceAsStream("/saml/idp-key.pk8"))));
        idpCredential = SecurityHelper.getSimpleCredential(idpCertificate.getPublicKey(), idpKey);

        final KeyPair spKeys = generator.generateKeyPair();
        spEncryptionCredential = SecurityHelper.getSimpleCredential(spKeys.getPublic(), null);
        forgerKeys = generator.generateKeyPair();

        final File metadata = File.createTempFile("idp-metadata", ".xml");
        metadata.deleteOnExit();
        Files.write(metadata.toPath(), idpMetadata(idpCertificate).getBytes(StandardCharsets.UTF_8));

        validator = new SamlValidator();
        final Method load = SamlValidator.class.getDeclaredMethod("loadSignatureTrustEngine", String.class);
        load.setAccessible(true);
        load.invoke(validator, metadata.getAbsolutePath());
        final Field privateKey = SamlValidator.class.getDeclaredField("privateKey");
        privateKey.setAccessible(true);
        privateKey.set(validator, (RSAPrivateKey) spKeys.getPrivate());
    }

    @Test
    public void signedPlainAssertionIsValid() throws Exception {
        final Response response = response();
        response.getAssertions().add(signed(assertion("attacker"), idpCredential));
        assertTrue(validator.validateSignature(marshall(response)));
    }

    @Test
    public void signedEncryptedAssertionIsValid() throws Exception {
        final Response response = response();
        response.getEncryptedAssertions().add(encrypt(signed(assertion("user"), idpCredential)));
        assertTrue(validator.validateSignature(marshall(response)));
    }

    @Test
    public void signedPlainAssertionDoesNotCoverForgedEncryptedOne() throws Exception {
        final Response response = response();
        response.getAssertions().add(signed(assertion("attacker"), idpCredential));
        response.getEncryptedAssertions().add(encrypt(assertion("victim")));
        try {
            final boolean valid = validator.validateSignature(marshall(response));
            assertFalse("forged encrypted assertion accepted", valid);
        } catch (Exception expected) {
            // refus attendu
        }
    }

    @Test
    public void unsignedEncryptedAssertionIsRejected() throws Exception {
        final Response response = response();
        response.getEncryptedAssertions().add(encrypt(assertion("victim")));
        try {
            final boolean valid = validator.validateSignature(marshall(response));
            assertFalse("unsigned assertion accepted", valid);
        } catch (Exception expected) {
            // refus attendu
        }
    }

    @Test
    public void assertionSignedByAnotherKeyIsRejected() throws Exception {
        final Credential forger = SecurityHelper.getSimpleCredential(forgerKeys.getPublic(), forgerKeys.getPrivate());
        final Response response = response();
        response.getAssertions().add(signed(assertion("victim"), forger));
        try {
            if (validator.validateSignature(marshall(response))) fail("assertion signed by an unknown key accepted");
        } catch (Exception expected) {
            // refus attendu
        }
    }

    private static Response response() {
        final Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.setID("_" + UUID.randomUUID());
        response.setIssueInstant(new DateTime());
        response.setVersion(SAMLVersion.VERSION_20);
        response.setIssuer(issuer());
        return response;
    }

    private static Assertion assertion(String nameId) {
        final Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID("_" + UUID.randomUUID());
        assertion.setIssueInstant(new DateTime());
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(issuer());
        final NameID name = build(NameID.DEFAULT_ELEMENT_NAME);
        name.setValue(nameId);
        final Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        subject.setNameID(name);
        assertion.setSubject(subject);
        return assertion;
    }

    private static Issuer issuer() {
        final Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(IDP);
        return issuer;
    }

    private static Assertion signed(Assertion assertion, Credential credential) throws Exception {
        final Signature signature = build(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(credential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        assertion.setSignature(signature);
        Configuration.getMarshallerFactory().getMarshaller(assertion).marshall(assertion);
        Signer.signObject(signature);
        return assertion;
    }

    private static EncryptedAssertion encrypt(Assertion assertion) throws Exception {
        final EncryptionParameters data = new EncryptionParameters();
        data.setAlgorithm(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128);
        final KeyEncryptionParameters key = new KeyEncryptionParameters();
        key.setEncryptionCredential(spEncryptionCredential);
        key.setAlgorithm(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP);
        final Encrypter encrypter = new Encrypter(data, key);
        encrypter.setKeyPlacement(Encrypter.KeyPlacement.INLINE);
        return encrypter.encrypt(assertion);
    }

    private static String marshall(Response response) throws Exception {
        return XMLHelper.nodeToString(Configuration.getMarshallerFactory().getMarshaller(response).marshall(response));
    }

    @SuppressWarnings("unchecked")
    private static <T> T build(javax.xml.namespace.QName name) {
        if (Signature.DEFAULT_ELEMENT_NAME.equals(name)) {
            return (T) Configuration.getBuilderFactory().getBuilder(name).buildObject(name);
        }
        return (T) ((SAMLObjectBuilder<?>) Configuration.getBuilderFactory().getBuilder(name)).buildObject();
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        try (java.io.InputStream input = in) {
            return input.readAllBytes();
        }
    }

    private static String idpMetadata(X509Certificate certificate) throws Exception {
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" "
                + "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" entityID=\"" + IDP + "\">"
                + "<md:IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
                + "<md:KeyDescriptor use=\"signing\"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>"
                + Base64.encodeBytes(certificate.getEncoded(), Base64.DONT_BREAK_LINES)
                + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>"
                + "<md:SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" "
                + "Location=\"https://idp.test/sso\"/>"
                + "</md:IDPSSODescriptor></md:EntityDescriptor>";
    }
}
