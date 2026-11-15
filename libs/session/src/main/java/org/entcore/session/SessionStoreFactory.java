package org.entcore.session;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class SessionStoreFactory {
    public static SessionStore createSessionStore(
            final Vertx vertx,
            final boolean isCluster,
            final JsonObject config) {
        final SessionStore sessionStore;
        if(config == null || !config.containsKey("session-store-class")) {
            sessionStore = new MapSessionStore(vertx, isCluster, config);
        } else {
            final String sessionStoreClass = config.getString("session-store-class");
            try {
                Constructor<?> constructor = Class.forName(sessionStoreClass)
                        .getConstructor(Vertx.class, Boolean.class, JsonObject.class);
                sessionStore = (SessionStore) constructor.newInstance(vertx, isCluster, config);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("To be used as a session store the class " + sessionStoreClass + " should implement a constructor with exactly 3 parameters Vertx.class, Boolean.class, JsonObject.class", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("The class " + sessionStoreClass + " could not be found in the classpath", e);
            } catch (InvocationTargetException | InstantiationException e) {
                throw new IllegalStateException("Error while creating an instance of session store " + sessionStoreClass, e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("The constructor of " + sessionStoreClass + " is not accessible. Make it public.", e);
            }
        }
        return sessionStore;
    }
}
