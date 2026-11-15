package org.entcore.common.redis;

import fr.wseduc.webutils.collections.SharedDataHelper;
import io.vertx.core.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Promise;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;

import static fr.wseduc.webutils.Utils.isNotEmpty;
import static org.entcore.common.utils.StringUtils.isEmpty;

public class RedisClient implements IRedisClient {
    public static final String ID_STREAM = "$id_stream";
    public static final String NAME_STREAM = "$name_stream";
    protected final RedisAPI client;
    protected final RedisOptions redisOptions;
    protected Logger log = LoggerFactory.getLogger(RedisClient.class);

    public RedisClient(final io.vertx.redis.client.Redis redis, final RedisOptions redisOptions) {
        this.client = RedisAPI.api(redis);
        this.redisOptions = redisOptions;
    }

    public RedisClient(final RedisAPI redis, final RedisOptions redisOptions) {
        this.client = redis;
        this.redisOptions = redisOptions;
    }


    /**
     * Creates a Redis client object from the supplied configuration or from the shared configuration.
     * @param vertx Vertx instance
     * @param config An object <b><u>containing a field redisConfig</u></b> which holds redis configuration
     * @return A client to call Redis
     */
    public static Future<RedisClient> create(final Vertx vertx, final JsonObject config) {
        if (config.getJsonObject("redisConfig") != null) {
            final JsonObject redisConfig = config.getJsonObject("redisConfig");
            final RedisClient redisClient = new RedisClient(vertx, redisConfig);
            return Future.succeededFuture(redisClient);
        }else{
          return SharedDataHelper.getInstance().<String, String>getLocalMulti("server", "redisConfig")
            .map(map -> map.get("redisConfig"))
            .map(redisConfig -> {
              if (redisConfig != null) {
                return new RedisClient(vertx, new JsonObject(redisConfig));
              } else {
                throw new InvalidParameterException("Missing redisConfig config");
              }
            });
        }
    }

    public static RedisOptions getOptions(final JsonObject redisConfig) {
        final boolean isSsl;
        if(redisConfig.containsKey("net-client-options")) {
            isSsl = redisConfig.getJsonObject("net-client-options").getBoolean("ssl");
        } else {
            isSsl = false;
        }
        final boolean isSentinel = "SENTINEL".equals(redisConfig.getString("type"));
        final String protocol = isSsl ? "rediss" : "redis";
        final String masterName = redisConfig.getString("masterName", "mymaster");
        final String masterFragment = isSentinel ? "#" + masterName : "";
        final List<String> redisConnectionStrings = new ArrayList<>();
        if (redisConfig.containsKey("hosts")) {
            final String username = redisConfig.getString("username", "");
            final String auth = redisConfig.getString("auth", ":");
            final String authFragment;
            if(isNotEmpty(username) || isNotEmpty(auth)) {
                authFragment = username + ":" + auth + "@";
            } else {
                authFragment = "";
            }
            final JsonArray hosts = redisConfig.getJsonArray("hosts");
            for (int i = 0; i < hosts.size(); i++) {
                final String host = hosts.getString(i);
                final String connecString = protocol + "://"+ authFragment + host + ":" + redisConfig.getInteger("port") + masterFragment;
                redisConnectionStrings.add(connecString);
            }
        } else {
            final String host = redisConfig.getString("host");
            final Integer port = redisConfig.getInteger("port");
            final String username = redisConfig.getString("username", "");
            final String password = redisConfig.getString("password");
            final String auth = redisConfig.getString("auth");
            final Integer select = redisConfig.getInteger("select", 0);
            if (isEmpty(password)) {
                if (isEmpty(auth)) {
                    redisConnectionStrings.add(String.format("%s://%s:%s/%s%s", protocol, host, port, select, masterFragment));
                } else {
                    redisConnectionStrings.add(String.format("%s://:%s@%s:%s/%s%s", protocol, auth, host, port, select, masterFragment));
                }
            } else {
                redisConnectionStrings.add(String.format("%s://%s:%s@%s:%s/%s%s", protocol, username, password, host, port, select, masterFragment));
            }
        }
        final RedisOptions redisOptions = new RedisOptions().setEndpoints(redisConnectionStrings);
        if(isSentinel) {
            redisOptions.setMasterName(masterName);
        }
        if(redisConfig.getInteger("maxWaitingHandlers") !=null){
            redisOptions.setMaxWaitingHandlers(redisConfig.getInteger("maxWaitingHandlers"));
        }
        if(redisConfig.getInteger("maxPoolSize") !=null){
            redisOptions.setMaxPoolSize(redisConfig.getInteger("maxPoolSize"));
        }
        if(redisConfig.getInteger("maxPoolWaiting") !=null){
            redisOptions.setMaxPoolWaiting(redisConfig.getInteger("maxPoolWaiting"));
        }
        if(redisConfig.getInteger("topology-cache-ttl") != null) {
            redisOptions.setTopologyCacheTTL(redisConfig.getInteger("topology-cache-ttl"));
        }if(redisConfig.getInteger("hash-slot-cache-ttl") != null) {
            redisOptions.setHashSlotCacheTTL(redisConfig.getInteger("hash-slot-cache-ttl"));
        }
        if(isNotEmpty(redisConfig.getString("type"))) {
            redisOptions.setType(RedisClientType.valueOf(redisConfig.getString("type")));
        }
        if(redisConfig.getBoolean("auto-failover") != null) {
            redisOptions.setAutoFailover(redisConfig.getBoolean("auto-failover"));
        }

        if(redisConfig.containsKey("net-client-options")) {
            redisOptions.setNetClientOptions(new NetClientOptions(redisConfig.getJsonObject("net-client-options")));
        }
        return redisOptions;
    }

    public RedisClient(final Vertx vertx, final JsonObject redisConfig) {
        this.redisOptions = getOptions(redisConfig);
        final io.vertx.redis.client.Redis oldClient = io.vertx.redis.client.Redis.createClient(vertx, getOptions(redisConfig));
        client = RedisAPI.api(oldClient);
    }

    /**
     * Performs a SCAN operation to retrieve all keys matching a pattern.
     * This is a safe alternative to KEYS command that avoids blocking Redis.
     *
     * @param pattern The pattern to match keys (e.g., "myprefix:*")
     * @param batch Optional count hint for each SCAN iteration (default: 100)
     * @return Future containing list of all matching keys
     */
    public Future<List<String>> scanAll(final String pattern, final int batch) {
        final Promise<List<String>> promise = Promise.promise();
        scanAllRecursive("0", pattern, batch, new ArrayList<>(), promise);
        return promise.future();
    }

    /**
     * Performs a SCAN operation to retrieve all keys matching a pattern with default count.
     *
     * @param pattern The pattern to match keys (e.g., "myprefix:*")
     * @return Future containing list of all matching keys
     */
    public Future<List<String>> scanAll(final String pattern) {
        return scanAll(pattern, 100);
    }

    /**
     * Recursive helper method for SCAN operation.
     *
     * @param cursor Current cursor position
     * @param pattern Pattern to match
     * @param count Count hint for SCAN
     * @param accumulator Accumulated results
     * @param promise Promise to complete when done
     */
    private void scanAllRecursive(final String cursor, final String pattern, final int count,
                                 final List<String> accumulator, final Promise<List<String>> promise) {
        final String[] scanArgs = new String[] {
                cursor,
                "MATCH", pattern,
                "COUNT", String.valueOf(count)
        };

        client.send(io.vertx.redis.client.Command.SCAN, scanArgs).onComplete(ar -> {
            if (ar.succeeded()) {
                final Response response = ar.result();
                final String nextCursor = response.get(0).toString();

                // Add keys from this iteration to accumulator
                if (response.size() > 1 && response.get(1) != null) {
                    final Response keys = response.get(1);
                    for (int i = 0; i < keys.size(); i++) {
                        accumulator.add(keys.get(i).toString());
                    }
                }

                // Continue scanning if cursor is not "0"
                if (!"0".equals(nextCursor)) {
                    scanAllRecursive(nextCursor, pattern, count, accumulator, promise);
                } else {
                    promise.complete(accumulator);
                }
            } else {
                log.error("SCAN operation failed", ar.cause());
                promise.fail(ar.cause());
            }
        });
    }

    /**
     * Expose configured RedisOptions for cases where a raw Redis connection is needed.
     * Minimal accessor to avoid breaking callers.
     *
     * @return RedisOptions used by this client
     */
    public RedisOptions getRedisOptions() {
        return this.redisOptions;
    }

    @Override
    public RedisAPI getClient() {
        return client;
    }

    @Override
    public RedisTransaction transaction() {
        return new RedisTransaction(this.client);
    }

}
