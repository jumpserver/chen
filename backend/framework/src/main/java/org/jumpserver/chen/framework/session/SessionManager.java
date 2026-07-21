package org.jumpserver.chen.framework.session;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class SessionManager {
    // 绑定创建 Chen 会话的 Servlet HTTP session，WS 握手时用它阻止 token 被跨浏览器重放。
    public static final String WEB_SESSION_ID_ATTRIBUTE = "webSessionId";
    private final static SessionManager instance = new SessionManager();
    private final static ThreadLocal<String> token = new ThreadLocal<>();
    private final Map<String, Session> store = new ConcurrentHashMap<>();
    // 每个 Chen 会话只能有一个主 /ws/session 连接，值为 WebSocket session id。
    private final Map<String, String> primaryWebSockets = new ConcurrentHashMap<>();

    public static String registerSession(Session session) {
        String token = createToken();
        session.setWebToken(token);
        instance.store.put(token, session);
        log.info("new session created, current session count {}", instance.getCurrentSessionCount());
        return token;
    }

    public static void unregisterSession(String token) {
        instance.store.remove(token);
        instance.primaryWebSockets.remove(token);
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

    public static boolean claimPrimaryWebSocket(String token, String webSocketId) {
        // 原子占用，避免并发握手同时替换当前主连接。
        String existing = instance.primaryWebSockets.putIfAbsent(token, webSocketId);
        return existing == null || existing.equals(webSocketId);
    }

    public static boolean releasePrimaryWebSocket(String token, String webSocketId) {
        // 只允许占用者释放，防止被拒绝的重放连接关闭正常会话。
        return instance.primaryWebSockets.remove(token, webSocketId);
    }


    private static String createToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }


}
