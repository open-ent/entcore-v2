package org.entcore.common.http.health;

import fr.wseduc.webutils.metrics.HealthCheckProbe;
import fr.wseduc.webutils.metrics.HealthCheckProbeResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.elasticsearch.ElasticClientManager;

import static io.vertx.core.Future.succeededFuture;

/**
 * Checks that OpenSearch/Elasticsearch is reachable and that the cluster is responsive.
 */
public class OpenSearchProbe implements HealthCheckProbe {
  private static final Logger log = LoggerFactory.getLogger(OpenSearchProbe.class);
  private Vertx vertx;
  private ElasticClientManager elasticClientManager;

  @Override
  public Future<Void> init(final Vertx vertx, final JsonObject config) {
    this.vertx = vertx;
    try {
      this.elasticClientManager = ElasticClientManager.create(vertx, vertx.getOrCreateContext().config());
      return succeededFuture();
    } catch (Exception e) {
      log.error("Failed to initialize OpenSearch probe", e);
      return Future.failedFuture(e);
    }
  }

  @Override
  public String getName() {
    return "opensearch";
  }

  @Override
  public Vertx getVertx() {
    return vertx;
  }

  @Override
  public Future<HealthCheckProbeResult> probe() {
    if (elasticClientManager == null) {
      return Future.succeededFuture(new HealthCheckProbeResult(getName(), false, 
        new JsonObject().put("error", "opensearch.not.initialized")));
    }

    final Promise<HealthCheckProbeResult> promise = Promise.promise();
    
    // Perform a simple cluster health check by doing a minimal count query
    // We use an empty query on a non-existent index pattern which should still return a response
    final JsonObject payload = new JsonObject().put("query", new JsonObject().put("match_all", new JsonObject()));
    
    elasticClientManager.getClient().count("_all", payload, 
      new org.entcore.common.elasticsearch.ElasticClient.ElasticOptions())
      .onSuccess(count -> {
        // If we get a response (even if count is 0), the cluster is healthy
        promise.tryComplete(new HealthCheckProbeResult(getName(), true, null));
      })
      .onFailure(th -> {
        log.error("Error while connecting to OpenSearch", th);
        promise.tryComplete(new HealthCheckProbeResult(getName(), false, 
          new JsonObject().put("error", th.getMessage())));
      });
    
    return promise.future();
  }
}
