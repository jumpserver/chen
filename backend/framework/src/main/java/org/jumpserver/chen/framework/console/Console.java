package org.jumpserver.chen.framework.console;


import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.context.ConsoleContext;

public interface Console {
    String getTitle();
    String getNodeKey();
    ConsoleContext getContext();
    void onInit(Connect connect);

    void handle(Packet packet);

    void close();
}
