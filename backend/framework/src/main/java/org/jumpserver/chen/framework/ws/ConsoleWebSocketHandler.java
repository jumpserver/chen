package org.jumpserver.chen.framework.ws;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.console.context.ConsoleContextResolutionException;
import org.jumpserver.chen.framework.console.context.ConsoleContextResolver;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.message.Message;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final Gson GSON = new Gson();
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session instanceof NativeWebSocketSession ns) {
            var nativeSession = ns.getNativeSession(jakarta.websocket.Session.class);
            if (nativeSession != null) {
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 90_000L);
            }
        }
    }


    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private final Map<String, SerialExecutor> sessionExecutors = new ConcurrentHashMap<>();

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        var token = (String) session.getAttributes().get("token");
        var packet = GSON.fromJson(message.getPayload().toString(), Packet.class);

        if (this.isQueryConsoleCancel(token, session.getId(), packet)) {
            this.cancelQueryConsole(token, session.getId());
            return;
        }

        if (this.isQueryConsoleMessage(token, session.getId(), packet)) {
            this.sessionExecutors
                    .computeIfAbsent(session.getId(), ignored -> new SerialExecutor(this.executorService))
                    .execute(() -> this.processMessage(session, token, packet));
        } else {
            this.executorService.submit(() -> this.processMessage(session, token, packet));
        }
    }

    private boolean isQueryConsoleMessage(String token, String sessionId, Packet packet) {
        if (this.sessionExecutors.containsKey(sessionId)) {
            return true;
        }
        if (StringUtils.equals(packet.getType(), Packet.TYPE_CONNECT)) {
            var connect = GSON.fromJson(GSON.toJson(packet.getData()), Connect.class);
            return StringUtils.equals(connect.getType(), Connect.CONSOLE_TYPE_QUERY);
        }
        var currentSession = SessionManager.getSession(token);
        return currentSession != null && currentSession.getConsoles().get(sessionId) instanceof QueryConsole;
    }

    private void processMessage(WebSocketSession session, String token, Packet packet) {
        try {
            SessionManager.setContext(token);
            if (StringUtils.equals(packet.getType(), Packet.TYPE_CONNECT)) {
                onConnectPacket(session, packet);
                return;
            }

            var currentSession = SessionManager.getCurrentSession();
            if (currentSession == null) {
                return;
            }
            var console = currentSession.getConsoles().get(session.getId());
            if (console != null) {
                this.setDatabaseContext(console);
                console.handle(packet);
            }
        } catch (Exception e) {
            log.error("handle message error", e);
            var serialExecutor = this.sessionExecutors.remove(session.getId());
            if (serialExecutor != null) {
                serialExecutor.shutdown();
            }
            this.closeConsole(token, session.getId());
            this.closeWebSocket(session);
        }
    }

    private boolean isQueryConsoleCancel(String token, String sessionId, Packet packet) {
        if (!StringUtils.equals(packet.getType(), Packet.TYPE_QUERY_CONSOLE_ACTION)) {
            return false;
        }
        var action = GSON.fromJson(GSON.toJson(packet.getData()), QueryConsoleAction.class);
        if (!StringUtils.equals(action.getAction(), QueryConsoleAction.ACTION_CANCEL)) {
            return false;
        }
        var currentSession = SessionManager.getSession(token);
        return currentSession != null && currentSession.getConsoles().get(sessionId) instanceof QueryConsole;
    }

    private void cancelQueryConsole(String token, String sessionId) {
        var currentSession = SessionManager.getSession(token);
        if (currentSession == null) {
            return;
        }
        var console = currentSession.getConsoles().get(sessionId);
        if (console instanceof QueryConsole queryConsole) {
            queryConsole.onCancel();
        }
    }

    private void onConnectPacket(WebSocketSession session, Packet packet) {
        Connect connect = GSON.fromJson(GSON.toJson(packet.getData()), Connect.class);
        var webSess = SessionManager.getCurrentSession();
        ConsoleContext context;
        try {
            context = new ConsoleContextResolver(webSess.getDatasource().getResourceBrowser())
                    .resolve(connect.getNodeKey(), connect.getType());
        } catch (ConsoleContextResolutionException e) {
            new PacketIO(session).sendPacket(
                    "show_message",
                    new Message(MessageLevel.ERROR, "Invalid console context")
            );
            return;
        }

        connect.setNodeKey(context.nodeKey());
        Console console = this.createConsole(connect.getType(), webSess.getDatasource(), session, context);
        if (console != null) {
            this.setDatabaseContext(console);
            webSess.getConsoles().put(session.getId(), console);
            console.onInit(connect);
            log.info("User {} open a console ", webSess.getUsername());
        }
    }

    protected Console createConsole(String type, org.jumpserver.chen.framework.datasource.Datasource datasource,
                                    WebSocketSession session, ConsoleContext context) {
        return switch (type) {
            case Connect.CONSOLE_TYPE_QUERY -> new QueryConsole(datasource, session, context);
            case Connect.CONSOLE_TYPE_DATA_VIEW -> new DataViewConsole(datasource, session, context);
            default -> null;
        };
    }

    private void setDatabaseContext(Console console) {
        var connectionManager = SessionManager.getCurrentSession().getDatasource().getConnectionManager();
        var db = console.getContext().database();
        if (StringUtils.isNotBlank(db)) {
            connectionManager.setDatabaseContext(db);
        }
    }


    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("websocket error", exception);
        this.closeSessionConsole(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        this.closeSessionConsole(session);
    }

    private void closeSessionConsole(WebSocketSession session) {
        var serialExecutor = this.sessionExecutors.remove(session.getId());
        if (serialExecutor != null) {
            serialExecutor.shutdown();
        }
        var token = (String) session.getAttributes().get("token");
        this.closeConsole(token, session.getId());
    }

    private void closeConsole(String token, String sessionId) {
        SessionManager.setContext(token);
        var currentSession = SessionManager.getCurrentSession();
        if (currentSession == null) {
            return;
        }
        Console console = currentSession.getConsoles().remove(sessionId);
        if (console != null) {
            console.close();
        }
    }

    private void closeWebSocket(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception e) {
            log.warn("close websocket session failed, sessionId={}", session.getId(), e);
        }
    }

    private static final class SerialExecutor {
        private final ExecutorService executor;
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private Runnable active;
        private boolean accepting = true;

        private SerialExecutor(ExecutorService executor) {
            this.executor = executor;
        }

        private synchronized void execute(Runnable task) {
            if (!this.accepting) {
                return;
            }
            this.tasks.offer(() -> {
                try {
                    task.run();
                } finally {
                    this.scheduleNext();
                }
            });
            if (this.active == null) {
                this.scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            this.active = this.tasks.poll();
            if (this.active != null) {
                this.executor.submit(this.active);
            }
        }

        private synchronized void shutdown() {
            this.accepting = false;
            this.tasks.clear();
        }
    }
}
