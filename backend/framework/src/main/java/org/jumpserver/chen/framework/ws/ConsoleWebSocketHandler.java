package org.jumpserver.chen.framework.ws;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.QueryConsole;
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
                        this.setDatabaseContext(console);

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
