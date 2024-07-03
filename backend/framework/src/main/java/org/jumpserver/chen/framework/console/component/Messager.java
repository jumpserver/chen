package org.jumpserver.chen.framework.console.component;

import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.ws.io.PacketIO;

public class Messager {
    private final PacketIO packetIO;

    public Messager(PacketIO packetIO) {
        this.packetIO = packetIO;
    }

    public void send(Message msg) {
        this.packetIO.sendPacket(Packet.TYPE_MESSAGE, msg);
    }

    public void sendGlobal(Message msg) {
        this.packetIO.sendPacket(Packet.TYPE_GLOBAL_MESSAGE, msg);
    }
}
