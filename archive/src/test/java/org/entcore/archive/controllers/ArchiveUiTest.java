package org.entcore.archive.controllers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Interface servie par défaut pour l'archivage : React, sauf demande contraire de la plateforme ; préférence de l'usager lue telle que stockée. */
public class ArchiveUiTest {

    @Test
    public void reactIsTheDefault() {
        assertEquals("react", ArchiveController.platformUi(new JsonObject()));
    }

    @Test
    public void platformCanAskForAngular() {
        assertEquals("angular", ArchiveController.platformUi(new JsonObject().put("frontend-ui", "angular")));
        assertEquals("react", ArchiveController.platformUi(new JsonObject().put("frontend-ui", "n'importe quoi")));
    }

    private static JsonObject rows(String preference) {
        return new JsonObject().put("status", "ok").put("result", new JsonArray().add(new JsonObject().put("preference", preference)));
    }

    @Test
    public void userPreferenceIsReadFromItsJsonString() {
        assertEquals("angular", ArchiveController.readUi(rows("{\"ui\":\"angular\"}"), "react"));
        assertEquals("react", ArchiveController.readUi(rows("{\"ui\":\"react\"}"), "angular"));
    }

    @Test
    public void missingOrInvalidPreferenceFallsBackOnThePlatformDefault() {
        assertEquals("react", ArchiveController.readUi(new JsonObject().put("status", "ok").put("result", new JsonArray()), "react"));
        assertEquals("angular", ArchiveController.readUi(rows(""), "angular"));
        assertEquals("angular", ArchiveController.readUi(rows("pas du json"), "angular"));
        assertEquals("react", ArchiveController.readUi(rows("{\"ui\":\"autre\"}"), "react"));
        assertEquals("react", ArchiveController.readUi(null, "react"));
    }

    @Test
    public void onlyAnOkReplyIsSuccessful() {
        assertTrue(ArchiveController.isOk(new JsonObject().put("status", "ok")));
        assertFalse(ArchiveController.isOk(new JsonObject().put("status", "error")));
        assertFalse(ArchiveController.isOk(null));
    }
}
