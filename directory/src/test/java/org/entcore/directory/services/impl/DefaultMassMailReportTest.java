package org.entcore.directory.services.impl;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** Bilan du publipostage par courriel : décompte, droits de lecture et courriel de bilan. */
public class DefaultMassMailReportTest {

    private static Vertx vertx;
    private static DefaultMassMailService service;

    @BeforeClass
    public static void setUp() {
        vertx = Vertx.vertx();
        service = new DefaultMassMailService(vertx, vertx.eventBus(), null, new JsonObject(), null);
    }

    @AfterClass
    public static void tearDown() {
        vertx.close();
    }

    private static UserInfos user(final String id, final boolean superAdmin) {
        final UserInfos u = new UserInfos();
        u.setUserId(id);
        if (superAdmin) {
            u.setFunctions(Collections.singletonMap(DefaultFunctions.SUPER_ADMIN, new UserInfos.Function()));
        }
        return u;
    }

    private static JsonObject account(final String id, final String email) {
        return new JsonObject().put("id", id).put("login", id + ".login").put("firstName", "Prénom")
                .put("lastName", id).put("profile", "Student").put("email", email).put("activationCode", "SECRET");
    }

    @Test
    public void countsResultsAndRestrictsReading() {
        final JsonArray withoutEmail = new JsonArray().add(new JsonObject().put("id", "c").put("login", "c.login"));
        final JsonObject job = service.startMailJob(user("admin", false), "structure-1", 3, 2, withoutEmail);

        DefaultMassMailService.recordMailResult(job, account("a", "a@example.org"), null);
        DefaultMassMailService.recordMailResult(job, account("b", "b@example.org"), "550 mailbox unavailable");

        final JsonObject report = service.massMailReport(job.getString("jobId"), user("admin", false));
        assertNotNull(report);
        assertEquals("running", report.getString("status"));
        assertEquals(3, (int) report.getInteger("total"));
        assertEquals(1, (int) report.getInteger("sent"));
        assertEquals(1, (int) report.getInteger("failed"));
        assertEquals(1, (int) report.getInteger("withoutEmail"));
        final JsonObject failure = report.getJsonArray("failures").getJsonObject(0);
        assertEquals("b", failure.getString("id"));
        assertEquals("550 mailbox unavailable", failure.getString("error"));
        assertFalse("le code d'activation ne doit pas figurer au bilan", failure.containsKey("activationCode"));
        assertFalse(report.containsKey("ownerId"));

        assertNull("un autre compte ne lit pas le bilan", service.massMailReport(job.getString("jobId"), user("other", false)));
        assertNotNull("le super-administrateur lit le bilan", service.massMailReport(job.getString("jobId"), user("other", true)));
        assertNull(service.massMailReport("unknown", user("admin", false)));

        final JsonObject summary = DefaultMassMailService.jobSummary(job);
        assertFalse(summary.containsKey("failures"));
        assertFalse(summary.containsKey("withoutEmailUsers"));
        assertEquals(job.getString("jobId"), summary.getString("jobId"));
    }

    @Test
    public void formatsDuration() {
        assertEquals("0 s", DefaultMassMailService.formatDuration(200));
        assertEquals("42 s", DefaultMassMailService.formatDuration(42_000));
        assertEquals("3 min 12 s", DefaultMassMailService.formatDuration(192_000));
        assertEquals("1 h 5 min", DefaultMassMailService.formatDuration(3_900_000));
    }

    @Test
    public void reportEmailEscapesAndTruncates() {
        final JsonArray withoutEmail = new JsonArray();
        for (int i = 0; i < 250; i++) {
            withoutEmail.add(new JsonObject().put("id", "u" + i).put("login", "u" + i).put("displayName", "Élève " + i));
        }
        final JsonObject job = service.startMailJob(user("admin", false), "structure-1", 251, 1, withoutEmail);
        DefaultMassMailService.recordMailResult(job, account("<script>", "x@example.org"), "<b>boom</b>");
        job.put("finishedAt", job.getLong("startedAt") + 5_000).put("durationMs", 5_000L).put("status", "done");

        final String html = service.reportEmailHtml(job, "Collège <Test>", "localhost", "fr");
        assertTrue(html.contains("Collège &lt;Test&gt;"));
        assertTrue(html.contains("&lt;b&gt;boom&lt;/b&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("5 s"));
        assertTrue(html.contains("Élève 199"));
        assertFalse("au-delà de 200 lignes la liste est tronquée", html.contains("Élève 200<"));
        assertTrue(html.contains("directory.massmail.report.truncated") || html.contains("tronquée"));
    }
}
