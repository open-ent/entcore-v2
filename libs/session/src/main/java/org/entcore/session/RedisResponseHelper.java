package org.entcore.session;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Response;

public final class RedisResponseHelper {

    public static JsonObject mapResponse(Response response) {
        final JsonObject res = new JsonObject();
        if (response != null && !response.getKeys().isEmpty()) {
            for (String key: response.getKeys()) {
                Response r = response.get(key);
                if (r != null) {
                    res.put(key, r.toString());
                }
            }
        }
        return res;
    }

    public static JsonArray listResponse(Response response) {
        final JsonArray res = new JsonArray();
        if (response != null) {
            for (Response r: response) {
                if (r != null) {
                    res.add(r.toString());
                }
            }
        }
        return res;
    }

}
