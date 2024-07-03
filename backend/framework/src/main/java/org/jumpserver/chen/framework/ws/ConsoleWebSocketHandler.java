package org.jumpserver.chen.framework.ws;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {


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

                var packet = JSON.parseObject(message.getPayload().toString(), Packet.class);
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
        Connect connect = JSON.parseObject(packet.getData().toString(), Connect.class);
        Console console = null;
        var webSess = SessionManager.getCurrentSession();

        switch (connect.getType()) {
            case Connect.CONSOLE_TYPE_QUERY -> {
                console = new QueryConsole(webSess.getDatasource(), session, connect.getNodeKey());
            }
            case Connect.CONSOLE_TYPE_DATA_VIEW -> {
                console = new DataViewConsole(webSess.getDatasource(), session, connect.getNodeKey());
            }
        }
        if (console != null) {
            webSess.getConsoles().put(session.getId(), console);

            var db = TreeUtils.getValue(console.getNodeKey(), "database");
            SessionManager.getCurrentSession().getDatasource().getConnectionManager().setDatabaseContext(db);

            console.onInit(connect);
            log.info("User {} open a console ", webSess.getUsername());
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
        console.close();
        SessionManager.getCurrentSession().getConsoles().remove(session.getId());
    }
}
