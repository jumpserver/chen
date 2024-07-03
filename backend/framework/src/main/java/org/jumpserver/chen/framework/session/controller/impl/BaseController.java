package org.jumpserver.chen.framework.session.controller.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.session.controller.message.Message;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;

@Slf4j
public class BaseController implements Controller {
    private final PacketIO packetIO;
    private Dialog currentDialog;

    public BaseController(PacketIO packetIO) {
        this.packetIO = packetIO;
    }

    @Override
    public void showDialog(Dialog dialog) {
        this.currentDialog = dialog;
        this.packetIO.sendPacket("show_dialog", dialog);
    }

    @Override
    public void closeDialog() {
        this.currentDialog = null;
        this.packetIO.sendPacket("close_dialog", null);
    }

    @Override
    public void showMessage(MessageLevel level, String message) {
        this.packetIO.sendPacket("show_message", new Message(level, message));
    }

    @Override
    public void handlePacket(Packet packet) {
        switch (packet.getType()) {
            case "dialog_event":
                this.onDialogEvent((String) packet.getData());
                break;
            default:
                break;
        }
    }

    @Override
    public void sendFile(String fileKey) {
        this.packetIO.sendPacket("download", fileKey);
    }

    private void onDialogEvent(String event) {
        if (this.currentDialog == null) {
            this.closeDialog();
            return;
        }
        try {
            var method = this.currentDialog.getEvent(event);
            if (method != null) {
                method.run();
            }
        } catch (Exception e) {
            this.showMessage(MessageLevel.ERROR, "handle event %s error:".formatted(event) + e.getMessage());
        }
    }

}
