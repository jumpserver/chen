package org.jumpserver.chen.framework.console;

import lombok.Getter;
import lombok.Setter;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.component.Messager;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;


@Setter
@Getter
public abstract class AbstractConsole implements Console {
    private final Logger consoleLogger;
    private final Datasource datasource;
    private final PacketIO packetIO;
    private final Messager messager;
    private String title;
    private final ConsoleContext context;

    public String getTitle() {
        return this.title;
    }

    public void onInit(Connect connect) {
        this.packetIO.sendPacket("init", Map.of("title", this.title));
    }

    public String getNodeKey() {
        return this.context.nodeKey();
    }

    protected AbstractConsole(Datasource datasource, WebSocketSession ws, ConsoleContext context) {
        this.context = context;
        this.datasource = datasource;
        this.packetIO = new PacketIO(ws);
        this.consoleLogger = new Logger(this.packetIO);
        this.messager = new Messager(this.packetIO);
    }
}
