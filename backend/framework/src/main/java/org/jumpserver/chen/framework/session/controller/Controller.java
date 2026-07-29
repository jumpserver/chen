package org.jumpserver.chen.framework.session.controller;

import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;

public interface Controller {
    void showDialog(Dialog dialog);

    default DialogHandle showDialog(Dialog dialog, Runnable onCancel) {
        this.showDialog(dialog);
        return DialogHandle.NOOP;
    }

    void closeDialog();

    default void bindDialogOwner(String owner) {
    }

    default String getDialogOwner() {
        return null;
    }

    default void clearDialogOwner() {
    }

    default void cancelDialogs(String owner) {
    }

    default void cancelCurrentDialog(String owner) {
        this.cancelDialogs(owner);
    }

    default boolean isDialogOwnerCancelled(String owner) {
        return false;
    }

    default void cancelAllDialogs() {
    }

    void showMessage(MessageLevel level, String message);

    void handlePacket(Packet packet);
    void sendFile(String fileKey);

}
