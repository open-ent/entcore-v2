package org.entcore.workspace.controllers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Le Content-Type d'un document vient de l'envoi : seuls les types qui ne peuvent pas exécuter de script
 * sur le domaine de l'ENT peuvent être servis en ligne.
 */
public class WorkspaceInlineContentTypeTest {

    private static boolean inlineAllowed(String contentType) {
        return WorkspaceController.isSafeInlineContentType(WorkspaceController.normalizeContentType(contentType));
    }

    @Test
    public void shouldServeMediaInline() {
        assertTrue(inlineAllowed("image/png"));
        assertTrue(inlineAllowed("IMAGE/JPEG"));
        assertTrue(inlineAllowed("application/pdf"));
        assertTrue(inlineAllowed("video/mp4"));
        assertTrue(inlineAllowed("audio/mpeg"));
        assertTrue(inlineAllowed("text/plain; charset=utf-8"));
        assertTrue(inlineAllowed("image/svg+xml"));
    }

    @Test
    public void shouldNotServeActiveContentInline() {
        assertFalse(inlineAllowed("text/html"));
        assertFalse(inlineAllowed("Text/HTML; charset=utf-8"));
        assertFalse(inlineAllowed("application/xhtml+xml"));
        assertFalse(inlineAllowed("text/xml"));
        assertFalse(inlineAllowed("application/javascript"));
        assertFalse(inlineAllowed(""));
        assertFalse(inlineAllowed(null));
    }
}
