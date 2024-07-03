package org.jumpserver.chen.framework.session;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class SessionManager {
    private final static SessionManager instance = new SessionManager();
    private final static ThreadLocal<String> token = new ThreadLocal<>();
    private final Map<String, Session> store = new ConcurrentHashMap<>();

    public static String registerSession(Session session) {
        String token = createToken();
        session.setWebToken(token);
        instance.store.put(token, session);
        log.info("new session created, current session count {}", instance.getCurrentSessionCount());
        return token;
    }

    public static void unregisterSession(String token) {
        instance.store.remove(token);
        log.info("session {} unregistered, current session count {}", token, instance.getCurrentSessionCount());
    }

    public int getCurrentSessionCount() {
        return instance.store.size();
    }

    public static void setContext(String token) {
        SessionManager.token.set(token);
    }

    public static String getContextToken() {
        return token.get();
    }

    public static SessionManager getInstance() {
        return instance;
    }

    public static Session getCurrentSession() {
        return instance.store.get(token.get());
    }

    public static Map<String, Session> getStore() {
        return instance.store;
    }

    public static Session getSession(String token) {
        return instance.store.get(token);
    }


    private static String createToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }


}
