package org.entcore.directory.controllers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Interface servie par défaut pour Mon compte et l'annuaire : l'ancienne tant que la migration n'est pas finie, sauf demande de la plateforme ; préférence de l'usager lue telle que stockée. */
public class UserBookUiTest {

    @Test
    public void angularIsTheDefaultUntilMigrationIsDone() {
        assertEquals("angular", UserBookController.platformUi(new JsonObject()));
    }

    @Test
    public void platformCanAskForReact() {
        assertEquals("react", UserBookController.platformUi(new JsonObject().put("frontend-ui", "react")));
        assertEquals("angular", UserBookController.platformUi(new JsonObject().put("frontend-ui", "n'importe quoi")));
    }

    private static JsonObject rows(String preference) {
        return new JsonObject().put("status", "ok").put("result", new JsonArray().add(new JsonObject().put("preference", preference)));
    }

    @Test
    public void userPreferenceIsReadFromItsJsonString() {
        assertEquals("angular", UserBookController.readUi(rows("{\"ui\":\"angular\"}"), "react"));
        assertEquals("react", UserBookController.readUi(rows("{\"ui\":\"react\"}"), "angular"));
    }

    @Test
    public void missingOrInvalidPreferenceFallsBackOnThePlatformDefault() {
        assertEquals("react", UserBookController.readUi(new JsonObject().put("status", "ok").put("result", new JsonArray()), "react"));
        assertEquals("angular", UserBookController.readUi(rows(""), "angular"));
        assertEquals("angular", UserBookController.readUi(rows("pas du json"), "angular"));
        assertEquals("react", UserBookController.readUi(rows("{\"ui\":\"autre\"}"), "react"));
        assertEquals("react", UserBookController.readUi(null, "react"));
    }

    @Test
    public void onlyAnOkReplyIsSuccessful() {
        assertTrue(UserBookController.isOk(new JsonObject().put("status", "ok")));
        assertFalse(UserBookController.isOk(new JsonObject().put("status", "error")));
        assertFalse(UserBookController.isOk(null));
    }
}
