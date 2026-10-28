package org.entcore.feeder.pim;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.test.TestHelper;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testcontainers.containers.Neo4jContainer;

/**
 * Retrait des accès privilégiés échus (Testcontainers Neo4j, base jetable).
 * Lancement : mvn -Drevision=6.14.9-patched -DtestContainerVersion=1.21.4 test -Dtest=PimExpiryJobTest
 */
@RunWith(VertxUnitRunner.class)
public class PimExpiryJobTest {

    private static final TestHelper test = TestHelper.helper();

    @ClassRule
    public static Neo4jContainer<?> neo4jContainer = test.database().createNeo4jContainer();

    @BeforeClass
    public static void setUp(TestContext context) throws Exception {
        test.database().initNeo4j(context, neo4jContainer);
    }

    @Test
    public void shouldRemoveOnlyExpiredFunctions(TestContext context) {
        final Async async = context.async();
        final long now = System.currentTimeMillis();
        test.database().executeNeo4j(
                "CREATE (s:Structure {id:'s1', name:'Lycée'}) " +
                "CREATE (adml:Function {externalId:'ADMIN_LOCAL', name:'AdminLocal'}) " +
                "CREATE (sa:Function {externalId:'SUPER_ADMIN', name:'SuperAdmin'}) " +
                "CREATE (fg:Group:FunctionGroup {id:'fg1', externalId:'s1-ADMIN_LOCAL'})-[:DEPENDS]->(s) " +
                "CREATE (expired:User {id:'expired'})-[:HAS_FUNCTION {scope:['s1'], validUntil:$past}]->(adml) " +
                "CREATE (expired)-[:IN {source:'MANUAL'}]->(fg) " +
                "CREATE (valid:User {id:'valid'})-[:HAS_FUNCTION {scope:['s1'], validUntil:$future}]->(adml) " +
                "CREATE (valid)-[:IN {source:'MANUAL'}]->(fg) " +
                "CREATE (:User {id:'permanent'})-[:HAS_FUNCTION]->(sa)",
                new JsonObject().put("past", now - 1000).put("future", now + 3_600_000))
                .compose(x -> new PimExpiryJob(test.vertx(), Neo4j.getInstance()).run())
                .compose(removed -> {
                    context.assertEquals(1, removed, "une seule fonction échue");
                    return test.database().executeNeo4j(
                            "MATCH (u:User) OPTIONAL MATCH (u)-[:HAS_FUNCTION]->(f) OPTIONAL MATCH (u)-[:IN]->(fg:FunctionGroup) " +
                            "RETURN u.id AS id, count(DISTINCT f) AS functions, count(DISTINCT fg) AS groups ORDER BY id",
                            new JsonObject());
                })
                .onComplete(context.asyncAssertSuccess(rows -> {
                    assertUser(context, rows.getJsonObject(0), "expired", 0, 0);
                    assertUser(context, rows.getJsonObject(1), "permanent", 1, 0);
                    assertUser(context, rows.getJsonObject(2), "valid", 1, 1);
                    async.complete();
                }));
    }

    // Les lignes sont des fr.wseduc.webutils.collections.JsonObject : equals() échoue
    // face à un io.vertx JsonObject de même contenu, d'où la comparaison champ par champ.
    private static void assertUser(TestContext context, JsonObject row, String id, int functions, int groups) {
        context.assertEquals(id, row.getString("id"));
        context.assertEquals(functions, row.getInteger("functions"), id + " : fonctions");
        context.assertEquals(groups, row.getInteger("groups"), id + " : groupes de fonction");
    }
}
