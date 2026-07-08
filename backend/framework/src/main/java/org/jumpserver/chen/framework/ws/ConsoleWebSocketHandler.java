package org.jumpserver.chen.framework.ws;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.datasource.ResourceBrowser;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.message.Message;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final Gson GSON = new Gson();
    private static final Set<String> QUERY_NODE_TYPES =
            Set.of("datasource", "database", "schema", "table");
    private static final Set<String> DATA_VIEW_NODE_TYPES = Set.of("table", "view");


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

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {

        executorService.submit(() -> {
            try {
                var token = (String) session.getAttributes().get("token");
                SessionManager.setContext(token);

                var packet = GSON.fromJson(message.getPayload().toString(), Packet.class);
                if (StringUtils.equals(packet.getType(), Packet.TYPE_CONNECT)) {
                    onConnectPacket(session, packet);
                } else {
                    var console = SessionManager
                            .getCurrentSession()
                            .getConsoles()
                            .get(session.getId());

                    if (console != null) {
                        var db = TreeUtils.getValue(console.getNodeKey(), "database");
                        SessionManager.getCurrentSession().getDatasource().getConnectionManager().setDatabaseContext(db);

                        var handler = SessionManager.getCurrentSession().getConsoles().get(session.getId());
                        handler.handle(packet);
                    }
                }
            } catch (Exception e) {
                log.error("handle message error", e);
            }
        });
    }

    private void onConnectPacket(WebSocketSession session, Packet packet) {
        Connect connect = GSON.fromJson(GSON.toJson(packet.getData()), Connect.class);
        var webSess = SessionManager.getCurrentSession();
        var node = this.resolveNode(
                webSess.getDatasource().getResourceBrowser(),
                connect.getNodeKey(),
                connect.getType()
        );
        if (node == null) {
            new PacketIO(session).sendPacket(
                    "show_message",
                    new Message(MessageLevel.ERROR, "Invalid console context")
            );
            return;
        }

        connect.setNodeKey(node.getKey());
        Console console = switch (connect.getType()) {
            case Connect.CONSOLE_TYPE_QUERY ->
                    new QueryConsole(webSess.getDatasource(), session, node.getKey());
            case Connect.CONSOLE_TYPE_DATA_VIEW ->
                    new DataViewConsole(webSess.getDatasource(), session, node.getKey());
            default -> null;
        };
        if (console != null) {
            webSess.getConsoles().put(session.getId(), console);

            var db = TreeUtils.getValue(console.getNodeKey(), "database");
            SessionManager.getCurrentSession().getDatasource().getConnectionManager().setDatabaseContext(db);

            console.onInit(connect);
            log.info("User {} open a console ", webSess.getUsername());
        }
    }

    TreeNode resolveNode(ResourceBrowser resourceBrowser, String nodeKey, String consoleType) {
        if (resourceBrowser == null || StringUtils.isBlank(nodeKey) || StringUtils.isBlank(consoleType)) {
            return null;
        }
        TreeNode node;
        try {
            var root = resourceBrowser.getTree();
            node = root == null ? null : TreeUtils.getNode(root, nodeKey);
        } catch (SQLException e) {
            return null;
        }
        if (node == null) {
            return null;
        }
        var allowedTypes = switch (consoleType) {
            case Connect.CONSOLE_TYPE_QUERY -> QUERY_NODE_TYPES;
            case Connect.CONSOLE_TYPE_DATA_VIEW -> DATA_VIEW_NODE_TYPES;
            default -> Set.<String>of();
        };
        return allowedTypes.contains(node.getType()) ? node : null;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("websocket error", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        var token = (String) session.getAttributes().get("token");
        SessionManager.setContext(token);
        var sess = SessionManager.getCurrentSession();
        if (sess == null) {
            return;
        }
        Console console = SessionManager
                .getCurrentSession()
                .getConsoles()
                .get(session.getId());
        if (console != null) {
            console.close();
        }
        SessionManager.getCurrentSession().getConsoles().remove(session.getId());
    }
}
