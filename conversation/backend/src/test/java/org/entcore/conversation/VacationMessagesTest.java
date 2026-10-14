/*
 * Copyright © Open ENT, 2026 — AGPL v3.
 */
package org.entcore.conversation;

import io.vertx.core.json.JsonObject;
import org.entcore.conversation.util.VacationMessages;
import org.junit.Assert;
import org.junit.Test;

/**
 * Période d'activité du message d'absence : la règle qui décide si une réponse automatique
 * part ou non. Les bornes sont des dates civiles incluses, chacune facultative.
 */
public class VacationMessagesTest {

    private static JsonObject vacation(boolean enabled, String start, String end) {
        return new JsonObject()
                .put("enabled", enabled)
                .put("startDate", start)
                .put("endDate", end)
                .put("body", "<p>Absent</p>");
    }

    @Test
    public void absenceDesactivee_neRepondJamais() {
        Assert.assertFalse(VacationMessages.isActive(vacation(false, null, null), "2026-10-14"));
        Assert.assertFalse(VacationMessages.isActive(vacation(false, "2026-10-01", "2026-10-31"), "2026-10-14"));
    }

    @Test
    public void reglagesAbsents_neRepondentPas() {
        Assert.assertFalse(VacationMessages.isActive(null, "2026-10-14"));
    }

    @Test
    public void sansBorne_absenceToujoursActive() {
        Assert.assertTrue(VacationMessages.isActive(vacation(true, null, null), "2026-10-14"));
        Assert.assertTrue(VacationMessages.isActive(vacation(true, "", "  "), "2026-10-14"));
    }

    @Test
    public void bornesIncluses() {
        final JsonObject v = vacation(true, "2026-10-10", "2026-10-20");
        Assert.assertTrue("premier jour inclus", VacationMessages.isActive(v, "2026-10-10"));
        Assert.assertTrue("milieu de période", VacationMessages.isActive(v, "2026-10-15"));
        Assert.assertTrue("dernier jour inclus", VacationMessages.isActive(v, "2026-10-20"));
    }

    @Test
    public void horsPeriode_neRepondPas() {
        final JsonObject v = vacation(true, "2026-10-10", "2026-10-20");
        Assert.assertFalse("la veille", VacationMessages.isActive(v, "2026-10-09"));
        Assert.assertFalse("le lendemain", VacationMessages.isActive(v, "2026-10-21"));
    }

    @Test
    public void borneUnique() {
        Assert.assertFalse(VacationMessages.isActive(vacation(true, "2026-11-02", null), "2026-10-31"));
        Assert.assertTrue(VacationMessages.isActive(vacation(true, "2026-11-02", null), "2026-12-25"));
        Assert.assertTrue(VacationMessages.isActive(vacation(true, null, "2026-11-02"), "2026-10-31"));
        Assert.assertFalse(VacationMessages.isActive(vacation(true, null, "2026-11-02"), "2026-11-03"));
    }

    @Test
    public void changementDAnnee_comparaisonLexicographiqueSure() {
        final JsonObject v = vacation(true, "2026-12-20", "2027-01-05");
        Assert.assertTrue(VacationMessages.isActive(v, "2026-12-31"));
        Assert.assertTrue(VacationMessages.isActive(v, "2027-01-01"));
        Assert.assertFalse(VacationMessages.isActive(v, "2027-01-06"));
        Assert.assertFalse(VacationMessages.isActive(v, "2026-12-19"));
    }
}
