package org.entcore.session;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.client.RedisAPI;

import java.util.ArrayList;
import java.util.List;

import static fr.wseduc.webutils.Utils.getOrElse;

public class RedisActivityManager implements ActivityManager {

    protected static final Logger logger = LoggerFactory.getLogger(RedisActivityManager.class);

    private final boolean activityEnabled;
    private final RedisAPI redisAPI;
    protected final long sessionTimeout;
    protected final long prolongedSessionTimeout;

    public RedisActivityManager(RedisAPI redisAPI, long sessionTimeout, long prolongedSessionTimeout, JsonObject config) {
        this.redisAPI = redisAPI;
        this.sessionTimeout = sessionTimeout;
        this.prolongedSessionTimeout = prolongedSessionTimeout;
        this.activityEnabled = getOrElse(config.getBoolean("inactivity"), false);
    }

    private long getTimeoutValue(boolean secureLocation) {
        return secureLocation ? prolongedSessionTimeout : sessionTimeout;
    }

    private String getTimeoutString(boolean secureLocation) {
        return "" + getTimeoutValue(secureLocation);
    }

    @Override
    public void getLastActivity(String sessionId, boolean secureLocation, Handler<AsyncResult<Long>> handler) {
        redisAPI.pttl(RedisSessionStore.SESSION_KEY + sessionId, ar -> {
            if (ar.succeeded()) {
                final long ttl = ar.result().toLong();
                if (ttl > 0) {
                    handler.handle(Future.succeededFuture(System.currentTimeMillis() + ttl - getTimeoutValue(secureLocation)));
                } else {
                    handler.handle(Future.failedFuture("expired key"));
                }
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });

    }

    @Override
    public void removeLastActivity(String sessionId, Handler<AsyncResult<Void>> handler) {
        // Not used in this implementation
    }

    @Override
    public void updateLastActivity(String sessionId, String userId, boolean secureLocation, Handler<AsyncResult<Void>> handler) {
        getLastActivity(sessionId, secureLocation, ar -> {
            if (ar.succeeded()) {
                final long now = System.currentTimeMillis();
                final long lastActivity = ar.result();
                if ((lastActivity + LAST_ACTIVITY_DELAY) < now) {
                    setExpireSession(sessionId, userId, secureLocation, handler);
                } else {
                    handler.handle(Future.succeededFuture());
                }
            } else {
                handler.handle(Future.failedFuture(ar.cause()));
            }
        });
    }

    public void setExpireSession(String sessionId, String userId, boolean secureLocation, Handler<AsyncResult<Void>> handler) {
        final List<String> args = new ArrayList<>();
        args.add(RedisSessionStore.SESSION_KEY + sessionId);
        args.add(getTimeoutString(secureLocation));
        redisAPI.pexpire(args)
        .onComplete(ar4 -> {
            if (ar4.succeeded()) {
                if (userId != null) {
                  args.clear();
                  args.add(RedisSessionStore.LOGIN_INFO_KEY + userId);
                  args.add(getTimeoutString(secureLocation));
                  redisAPI.pexpire(args).onComplete(ar5 -> {
                        if (ar5.succeeded()) {
                            handler.handle(Future.succeededFuture());
                        } else {
                            handler.handle(Future.failedFuture(ar5.cause()));
                        }
                    });
                }
                else {
                    handler.handle(Future.succeededFuture());
                }
            } else {
                logger.error("Error when pexpire put session tx", ar4.cause());
                handler.handle(Future.failedFuture(ar4.cause()));
            }
        });
    }

    @Override
    public boolean isEnabled() {
        return activityEnabled;
    }

}
