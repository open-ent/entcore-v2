package org.entcore.workspace.controllers;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Interface servie par défaut quand l'usager n'a rien choisi : React, sauf demande contraire de la plateforme
 * ou mode « lazy » (gros volumes) que React ne reprend pas encore.
 */
public class WorkspacePlatformUiTest {

    private static JsonObject lazy(boolean value) {
        return new JsonObject().put("publicConf", new JsonObject().put("lazy-mode", value));
    }

    @Test
    public void reactIsTheDefault() {
        assertEquals("react", WorkspaceController.platformUi(new JsonObject()));
    }

    @Test
    public void platformCanAskForAngular() {
        assertEquals("angular", WorkspaceController.platformUi(new JsonObject().put("frontend-ui", "angular")));
    }

    @Test
    public void lazyModeKeepsAngularByDefault() {
        assertEquals("angular", WorkspaceController.platformUi(lazy(true)));
        assertEquals("react", WorkspaceController.platformUi(lazy(false)));
    }

    @Test
    public void explicitChoiceBeatsLazyMode() {
        assertEquals("react", WorkspaceController.platformUi(lazy(true).put("frontend-ui", "react")));
        assertEquals("angular", WorkspaceController.platformUi(lazy(false).put("frontend-ui", "angular")));
    }

    @Test
    public void unknownValueFallsBackOnTheDefault() {
        assertEquals("react", WorkspaceController.platformUi(new JsonObject().put("frontend-ui", "n'importe quoi")));
    }

    @Test
    public void onlyAnOkStatusIsASuccessfulAnswer() {
        assertEquals(true, WorkspaceController.isOk(new JsonObject().put("status", "ok")));
        assertEquals(false, WorkspaceController.isOk(new JsonObject().put("status", "error").put("message", "SyntaxException")));
        assertEquals(false, WorkspaceController.isOk(new JsonObject()));
        assertEquals(false, WorkspaceController.isOk(null));
    }
}
