package org.jumpserver.chen.framework.ws.io;

import com.alibaba.fastjson.JSON;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;


@Slf4j
public class PacketIO {

    @Getter
    private final WebSocketSession wsSession;

    public PacketIO(WebSocketSession ws) {
        this.wsSession = ws;
    }

    public void sendPacket(Packet packet) {
        synchronized (this.wsSession) {
            try {
                String json = JSON.toJSONStringWithDateFormat(packet, "yyyy-MM-dd HH:mm:ss");
                this.wsSession.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error(e.getMessage());

            }
        }
    }

    public void sendPacket(String type, Object data) {
        Packet packet = new Packet();
        packet.setType(type);
        packet.setData(data);
        this.sendPacket(packet);
    }

    public void close() {
        try {
            this.wsSession.close();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

}
