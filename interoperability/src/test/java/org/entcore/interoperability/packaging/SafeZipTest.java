package org.entcore.interoperability.packaging;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Un paquet vient de l'extérieur de la plateforme : ces contrôles sont la première ligne de
 * défense, appliquée avant toute écriture.
 */
public class SafeZipTest {

    @Test
    public void accepteUnCheminRelatifNormal() {
        assertTrue(SafeZip.isEntryNameSafe("resources/blog/resources.json"));
        assertTrue(SafeZip.isEntryNameSafe("oeip-manifest.json"));
        assertTrue(SafeZip.isEntryNameSafe("resources/blog/content/fi/file-0001/squelette.png"));
    }

    @Test
    public void refuseLaRemonteeDeRepertoire() {
        assertFalse(SafeZip.isEntryNameSafe("../etc/passwd"));
        assertFalse(SafeZip.isEntryNameSafe("resources/../../etc/passwd"));
        assertFalse(SafeZip.isEntryNameSafe("resources/blog/.."));
    }

    @Test
    public void refuseUnCheminAbsolu() {
        assertFalse(SafeZip.isEntryNameSafe("/etc/passwd"));
        assertFalse(SafeZip.isEntryNameSafe("C:/windows/system32"));
    }

    @Test
    public void refuseAntislashEtCaracteresDeControle() {
        assertFalse(SafeZip.isEntryNameSafe("resources\\blog\\x.json"));
        assertFalse(SafeZip.isEntryNameSafe("resources/blog\u0000.json"));
    }

    @Test
    public void refuseUnNomVide() {
        assertFalse(SafeZip.isEntryNameSafe(""));
        assertFalse(SafeZip.isEntryNameSafe(null));
    }

    @Test
    public void detecteUnRapportDeCompressionSuspect() {
        assertNull(SafeZip.rejectRatio(1000, 5000, SafeZip.DEFAULT_MAX_RATIO));
        assertNotNull(SafeZip.rejectRatio(1000, 1000L * 5000, SafeZip.DEFAULT_MAX_RATIO));
        // Taille inconnue : le plafond devra être appliqué pendant la lecture du flux.
        assertNull(SafeZip.rejectRatio(-1, -1, SafeZip.DEFAULT_MAX_RATIO));
    }

    @Test
    public void expliqueLeMotifDuRefus() {
        String reason = SafeZip.rejectEntryName("../x");
        assertNotNull(reason);
        assertTrue(reason.contains("remontée"));
    }
}
