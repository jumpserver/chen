package org.jumpserver.chen.framework.ws;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
public class SessionWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {

        if (session instanceof NativeWebSocketSession ns) {
            var nativeSession = ns.getNativeSession(jakarta.websocket.Session.class);
            if (nativeSession != null){
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 90_000L);
            }
        }

        var token = (String) session.getAttributes().get("token");
        SessionManager.setContext(token);

        Session sess = SessionManager.getCurrentSession();
        sess.activeSession(new PacketIO(session));

        Thread.sleep(300);

        Dialog dialog = new Dialog(MessageUtils.get("msg.dialog.title.init_datasource"));
        dialog.setBody(MessageUtils.get("msg.dialog.message.init_datasource"));

        sess.getController().showDialog(dialog);

        Thread.sleep(300);

        try {
            sess.getDatasource().ping();
            sess.getDatasource().init();
        } catch (Exception e) {
            dialog.setTitle(MessageUtils.get("msg.dialog.title.init_datasource_failed"));
            dialog.setBodyType("html");

            dialog.setBody("""
                    <div style="display: inline-block;text-align: left">
                        %s<br/>
                        %s: <span style="color: red">%s</span>
                    </div>
                    """.formatted(
                    MessageUtils.get("msg.dialog.message.init_datasource_failed"),
                    MessageUtils.get("msg.dialog.title.error_message"),
                    e.getMessage()));


            sess.getController().showDialog(dialog);
            session.close();
            return;
        }

        sess.getController().closeDialog();


        sess.getPacketIO().sendPacket("set_ready", null);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        var token = (String) session.getAttributes().get("token");
        SessionManager.setContext(token);
        var webSession = SessionManager.getCurrentSession();

        var packet = JSON.parseObject(message.getPayload().toString(), Packet.class);
        switch (packet.getType()) {
            case "ping" -> {
                log.debug("receive ping packet from session {}", webSession.getUsername());
                webSession.getPacketIO().sendPacket("pong", null);
            }
            default -> webSession.getController().handlePacket(packet);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        var token = (String) session.getAttributes().get("token");
        SessionManager.setContext(token);
        var sess = SessionManager.getCurrentSession();
        if (sess != null) {
            sess.close();
        }
    }
}