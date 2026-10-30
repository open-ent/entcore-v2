package org.entcore.timeline.controllers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Interface servie par défaut pour Mon compte et l'annuaire : React, sauf demande contraire de la plateforme ; préférence de l'usager lue telle que stockée. */
public class TimelineUiTest {

    @Test
    public void reactIsTheDefault() {
        assertEquals("react", TimelineController.platformUi(new JsonObject()));
    }

    @Test
    public void platformCanAskForAngular() {
        assertEquals("angular", TimelineController.platformUi(new JsonObject().put("frontend-ui", "angular")));
        assertEquals("react", TimelineController.platformUi(new JsonObject().put("frontend-ui", "n'importe quoi")));
    }

    private static JsonObject rows(String preference) {
        return new JsonObject().put("status", "ok").put("result", new JsonArray().add(new JsonObject().put("preference", preference)));
    }

    @Test
    public void userPreferenceIsReadFromItsJsonString() {
        assertEquals("angular", TimelineController.readUi(rows("{\"ui\":\"angular\"}"), "react"));
        assertEquals("react", TimelineController.readUi(rows("{\"ui\":\"react\"}"), "angular"));
    }

    @Test
    public void missingOrInvalidPreferenceFallsBackOnThePlatformDefault() {
        assertEquals("react", TimelineController.readUi(new JsonObject().put("status", "ok").put("result", new JsonArray()), "react"));
        assertEquals("angular", TimelineController.readUi(rows(""), "angular"));
        assertEquals("angular", TimelineController.readUi(rows("pas du json"), "angular"));
        assertEquals("react", TimelineController.readUi(rows("{\"ui\":\"autre\"}"), "react"));
        assertEquals("react", TimelineController.readUi(null, "react"));
    }

    @Test
    public void onlyAnOkReplyIsSuccessful() {
        assertTrue(TimelineController.isOk(new JsonObject().put("status", "ok")));
        assertFalse(TimelineController.isOk(new JsonObject().put("status", "error")));
        assertFalse(TimelineController.isOk(null));
    }
}
