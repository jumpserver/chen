package org.jumpserver.chen.framework.session.controller.impl;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.session.controller.DialogHandle;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.session.controller.message.Message;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class BaseController implements Controller {
    private static final Gson GSON = new Gson();
    private static final String SESSION_DIALOG_OWNER = "session";

    private final PacketIO packetIO;
    private final Object dialogLock = new Object();
    private final ThreadLocal<String> dialogOwner = new ThreadLocal<>();
    private final Set<String> cancelledOwners = new HashSet<>();
    private ActiveDialog currentDialog;
    private boolean allDialogsCancelled;

    public BaseController(PacketIO packetIO) {
        this.packetIO = packetIO;
    }

    @Override
    public void showDialog(Dialog dialog) {
        this.showDialog(dialog, () -> {
        });
    }

    @Override
    public DialogHandle showDialog(Dialog dialog, Runnable onCancel) {
        String id = UUID.randomUUID().toString();
        dialog.setId(id);
        ActiveDialog activeDialog = new ActiveDialog(
                id,
                this.dialogOwner.get() == null ? SESSION_DIALOG_OWNER : this.dialogOwner.get(),
                dialog,
                onCancel
        );
        ActiveDialog replaced;
        boolean ownerCancelled;
        synchronized (this.dialogLock) {
            ownerCancelled = this.allDialogsCancelled || this.cancelledOwners.contains(activeDialog.owner());
            if (ownerCancelled) {
                replaced = null;
            } else {
                replaced = this.currentDialog;
                this.currentDialog = activeDialog;
                this.packetIO.sendPacket("show_dialog", dialog);
            }
        }
        if (ownerCancelled) {
            runCancel(activeDialog);
            return DialogHandle.NOOP;
        }
        if (replaced != null) {
            log.warn("replace unfinished dialog, oldDialogId={}, newDialogId={}", replaced.id(), id);
            runCancel(replaced);
        }
        return new DialogHandle() {
            @Override
            public void cancel() {
                BaseController.this.finishDialog(id, true);
            }

            @Override
            public void close() {
                BaseController.this.finishDialog(id, false);
            }
        };
    }

    @Override
    public void closeDialog() {
        ActiveDialog activeDialog;
        synchronized (this.dialogLock) {
            activeDialog = this.currentDialog;
            if (activeDialog == null) {
                this.packetIO.sendPacket("close_dialog", null);
                return;
            }
        }
        this.finishDialog(activeDialog.id(), true);
    }

    @Override
    public void bindDialogOwner(String owner) {
        this.dialogOwner.set(owner);
    }

    @Override
    public String getDialogOwner() {
        return this.dialogOwner.get() == null ? SESSION_DIALOG_OWNER : this.dialogOwner.get();
    }

    @Override
    public void clearDialogOwner() {
        this.dialogOwner.remove();
    }

    @Override
    public void cancelDialogs(String owner) {
        synchronized (this.dialogLock) {
            this.cancelledOwners.add(owner);
        }
        this.cancelCurrentDialog(owner);
    }

    @Override
    public void cancelCurrentDialog(String owner) {
        ActiveDialog activeDialog;
        synchronized (this.dialogLock) {
            activeDialog = this.currentDialog;
            if (activeDialog == null || !activeDialog.owner().equals(owner)) {
                return;
            }
            this.currentDialog = null;
            this.packetIO.sendPacket("close_dialog", null);
        }
        runCancel(activeDialog);
    }

    @Override
    public boolean isDialogOwnerCancelled(String owner) {
        synchronized (this.dialogLock) {
            return this.allDialogsCancelled || this.cancelledOwners.contains(owner);
        }
    }

    @Override
    public void cancelAllDialogs() {
        ActiveDialog activeDialog;
        synchronized (this.dialogLock) {
            this.allDialogsCancelled = true;
            activeDialog = this.currentDialog;
            if (activeDialog == null) {
                return;
            }
            this.currentDialog = null;
            this.packetIO.sendPacket("close_dialog", null);
        }
        runCancel(activeDialog);
    }

    @Override
    public void showMessage(MessageLevel level, String message) {
        this.packetIO.sendPacket("show_message", new Message(level, message));
    }

    @Override
    public void handlePacket(Packet packet) {
        switch (packet.getType()) {
            case "dialog_event": {
                try {
                    var event = GSON.fromJson(GSON.toJson(packet.getData()), DialogEvent.class);
                    if (event != null) {
                        this.onDialogEvent(event.dialogId(), event.event());
                    }
                } catch (RuntimeException e) {
                    log.warn("ignore invalid dialog event");
                }
                break;
            }
            case "input_active":
                this.refreshLastActiveTime();
                break;
            default:
                break;
        }
    }


    private void refreshLastActiveTime() {
        var session = SessionManager.getCurrentSession();
        session.refreshLastActiveTime();
    }

    @Override
    public void sendFile(String fileKey) {
        this.packetIO.sendPacket("download", fileKey);
    }

    private void onDialogEvent(String dialogId, String event) {
        synchronized (this.dialogLock) {
            ActiveDialog activeDialog = this.currentDialog;
            if (activeDialog == null || !activeDialog.id().equals(dialogId)) {
                log.warn("ignore stale dialog event, dialogId={}, event={}", dialogId, event);
                return;
            }
            try {
                var method = activeDialog.dialog().getEvent(event);
                if (method != null) {
                    method.run();
                }
            } catch (Exception e) {
                this.showMessage(MessageLevel.ERROR, "handle event %s error:".formatted(event) + e.getMessage());
            }
        }
    }

    private void finishDialog(String id, boolean cancel) {
        ActiveDialog activeDialog;
        synchronized (this.dialogLock) {
            if (this.currentDialog == null || !this.currentDialog.id().equals(id)) {
                return;
            }
            activeDialog = this.currentDialog;
            this.currentDialog = null;
            this.packetIO.sendPacket("close_dialog", null);
        }
        if (cancel) {
            runCancel(activeDialog);
        }
    }

    private static void runCancel(ActiveDialog activeDialog) {
        try {
            activeDialog.onCancel().run();
        } catch (RuntimeException e) {
            log.warn("cancel dialog failed, dialogId={}", activeDialog.id(), e);
        }
    }

    private record ActiveDialog(String id, String owner, Dialog dialog, Runnable onCancel) {
    }

    private record DialogEvent(String dialogId, String event) {
    }

}
