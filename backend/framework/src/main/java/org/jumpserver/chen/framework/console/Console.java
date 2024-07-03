package org.jumpserver.chen.framework.console;


import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.console.entity.request.Connect;

public interface Console {
    String getTitle();
    String getNodeKey();
    void onInit(Connect connect);

    void handle(Packet packet);

    void close();
}
