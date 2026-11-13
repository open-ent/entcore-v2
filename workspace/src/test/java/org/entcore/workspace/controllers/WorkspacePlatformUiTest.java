package org.entcore.workspace.controllers;

import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Interface servie par défaut quand l'usager n'a rien choisi : React, sauf demande contraire de la plateforme
 * Le mode « lazy » (gros volumes) est repris par React : il ne change pas l'IHM par défaut.
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
    public void lazyModeNoLongerChangesDefault() {
        assertEquals("react", WorkspaceController.platformUi(lazy(true)));
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
    public void urlOverrideWinsOtherwiseThePlatformDecides() {
        assertEquals("react", WorkspaceController.servedUi(null, "react"));
        assertEquals("angular", WorkspaceController.servedUi("angular", "react"));
        assertEquals("react", WorkspaceController.servedUi("react", "angular"));
        assertEquals("react", WorkspaceController.servedUi("n'importe quoi", "react"));
    }
}
