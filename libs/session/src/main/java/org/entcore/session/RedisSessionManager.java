/* Copyright © "Open Digital Education", 2019
 *
 * This program is published by "Open Digital Education".
 *
 */

package org.entcore.session;

import io.vertx.core.Promise;

public class RedisSessionManager extends AuthManager {

	@Override
	public void start(Promise<Void> startPromise) {
		final Promise<Void> promise = Promise.promise();
		super.start(promise);
		promise.future().onSuccess(x -> {
			logger.info("in start redis session");
			sessionStore = new RedisSessionStore(vertx, cluster, config);
			final String address = getOptionalStringConfig("address", "wse.session");
			eb.consumer(address, this);
			logger.info("in start redis session end");
			startPromise.tryComplete();
		}).onFailure(ex -> logger.error("Error when start session redis", ex));
	}

}
