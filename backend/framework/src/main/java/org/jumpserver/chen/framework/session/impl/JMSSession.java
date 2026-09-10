package org.jumpserver.chen.framework.session.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.ACLFilter;
import org.jumpserver.chen.framework.jms.CommandHandler;
import org.jumpserver.chen.framework.jms.ReplayHandler;
import org.jumpserver.chen.framework.jms.acl.ACLCommandContext;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.jms.impl.CommandHandlerImpl;
import org.jumpserver.chen.framework.jms.impl.ReplayHandlerImpl;
import org.jumpserver.chen.framework.session.QueryAuditFunction;
import org.jumpserver.chen.framework.session.MetadataQueryAuditFunction;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.session.exception.SessionException;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.wisp.Common;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JMSSession extends BaseSession {

    private static final int METADATA_AUDIT_ERROR_MAX_LENGTH = 1024;

    @Getter
    private final Common.Session jmsSession;

    @Getter
    private List<Common.DataMaskingRule> dataMaskingRules;
    private ACLFilter aclFilter;
    private CommandHandler commandHandler;
    private ReplayHandler replayHandler;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;

    private final List<Common.CommandACL> commandACLs;
    private final long maxIdleTimeDelta;
    private final long expireTime;

    private LocalDateTime maxSessionEndTime;
    private int maxSessionEndHours;
    private LocalDateTime dynamicEndTime;
    private String dynamicEndReason;

    private Thread waitIdleTimeThread;
    @Setter
    private String gatewayId;


    @Getter
    private boolean locked = false;

    @Getter
    private String lockCreator;

    private boolean canUpload = false;
    private boolean canDownload = false;

    private boolean canCopy = false;
    private boolean canPaste = false;

    private boolean closed = false;


    public void lockSession(String creator) {
        SessionManager.setContext(this.getWebToken());

        this.lockCreator = creator;
        this.locked = true;
        this.getController().showMessage(MessageLevel.ERROR, MessageUtils.get("SessionLockedMessage", this.lockCreator));
    }

    public void unloadSession(String creator) {
        SessionManager.setContext(this.getWebToken());

        this.getController().showMessage(MessageLevel.SUCCESS, MessageUtils.get("SessionUnlockedMessage", creator));
        this.locked = false;
        this.lockCreator = null;
    }


    public JMSSession(Common.Session session,
                      Datasource datasource,
                      String remoteAddr,
                      ServiceGrpc.ServiceBlockingStub serviceBlockingStub,
                      ServiceOuterClass.TokenResponse tokenResp) {
        super(datasource, remoteAddr);
        this.jmsSession = session;
        this.serviceBlockingStub = serviceBlockingStub;
        this.commandACLs = tokenResp.getData().getFilterRulesList();
        this.expireTime = tokenResp.getData().getExpireInfo().getExpireAt();
        this.maxIdleTimeDelta = tokenResp.getData().getSetting().getMaxIdleTime();
        this.maxSessionEndHours = tokenResp.getData().getSetting().getMaxSessionTime();
        this.maxSessionEndTime = LocalDateTime.now().plusHours(tokenResp.getData().getSetting().getMaxSessionTime());
        this.dynamicEndTime = this.maxSessionEndTime;

        this.canUpload = tokenResp.getData().getPermission().getEnableUpload();
        this.canDownload = tokenResp.getData().getPermission().getEnableDownload();
        this.canCopy = tokenResp.getData().getPermission().getEnableCopy();
        this.canPaste = tokenResp.getData().getPermission().getEnablePaste();
        this.dataMaskingRules = tokenResp.getData().getDataMaskingRulesList();
    }


    public void setDynamicEndInfo(String reason) {

        SessionManager.setContext(this.getWebToken());

        this.dynamicEndReason = reason;
        this.dynamicEndTime = LocalDateTime.now().plusMinutes(10);

        var dialog = new Dialog(MessageUtils.get("PermissionExpiredDialogTitle"));

        dialog.setBody(MessageUtils.get("PermissionExpiredDialogMessage"));

        dialog.addButton(new Button(MessageUtils.get("Cancel"), "cancel", () -> this.getController().closeDialog()));

        this.getController().showDialog(dialog);

    }

    public void resetDynamicEndInfo() {
        this.dynamicEndReason = "";
        this.dynamicEndTime = this.maxSessionEndTime;
    }


    @Override
    public void recordCommand(String command) {
        CommandRecord commandRecord = new CommandRecord(command);
        this.recordCommand(commandRecord);
    }

    @Override
    public ACLResult checkACL(String command) {
        return this.aclFilter.commandACLFilterWithContext(command, ACLCommandContext.executionOwned(null));
    }

    public ACLResult checkACL(String command, Connection connection) {
        return this.aclFilter.commandACLFilterWithContext(command, ACLCommandContext.queryConsoleOwned(connection));
    }

    @Override
    public ACLResult checkACLWithContext(String command, ACLCommandContext context) {
        return this.aclFilter.commandACLFilterWithContext(command, context);
    }

    @Override
    public void recordCommand(CommandRecord commandRecord) {
        this.commandHandler.recordCommand(commandRecord);
    }

    @Override
    public boolean canUpload() {
        return this.canUpload;
    }

    @Override
    public boolean canDownload() {
        return this.canDownload;
    }

    @Override
    public boolean canCopy() {
        return this.canCopy;
    }

    @Override
    public boolean canPaste() {
        return this.canPaste;
    }

    @Override
    public String getUsername() {
        return this.jmsSession.getUser();
    }

    public String getDatasourceName() {
        return this.jmsSession.getAsset();
    }

    @Override
    public void activeSession(PacketIO packetIO) {
        this.commandHandler = new CommandHandlerImpl(this.jmsSession, this.serviceBlockingStub);
        this.replayHandler = new ReplayHandlerImpl(this.jmsSession, this.serviceBlockingStub);
        this.aclFilter = new ACLFilterImpl(this.jmsSession, this.serviceBlockingStub, this.commandACLs);
        this.replayHandler.init();
        super.activeSession(packetIO);
        this.startWaitIdleTime();
        this.recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType.AssetConnectSuccess, "");
    }


    private void recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType eventType, String reason) {
        var req = ServiceOuterClass.SessionLifecycleLogRequest.newBuilder()
                .setSessionId(this.jmsSession.getId())
                .setEvent(eventType)
                .setReason(reason)
                .build();
        var resp = this.serviceBlockingStub.recordSessionLifecycleLog(req);
        if (!resp.getStatus().getOk()) {
            log.error("recordLifecycle error: {}", resp.getStatus().getErr());
        }
    }

    private void startWaitIdleTime() {
        this.refreshLastActiveTime();

        var token = SessionManager.getContextToken();

        this.waitIdleTimeThread = new Thread(() -> {
            SessionManager.setContext(token);

            while (this.isActive()) {
                try {
                    Thread.sleep(5000);

                    synchronized (this) {
                        var expireTime = LocalDateTime.ofEpochSecond(this.expireTime, 0, ZoneOffset.ofHours(8));

                        if (LocalDateTime.now().isAfter(expireTime)) {
                            this.close("PermissionsExpiredOn", "permission_expired", expireTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                            return;
                        }

                        if (Math.abs(Duration.between(LocalDateTime.now(), this.getLastActiveTime()).toMinutes()) > this.maxIdleTimeDelta) {
                            this.close("OverMaxIdleTimeError", "idle_disconnect", this.maxIdleTimeDelta);
                            return;
                        }

                        if (LocalDateTime.now().isAfter(this.maxSessionEndTime)) {
                            this.close("OverMaxSessionTimeError", "max_session_timeout", this.maxSessionEndHours);
                            return;
                        }

                        if (LocalDateTime.now().isAfter(this.dynamicEndTime)) {
                            this.close("PermissionAlreadyExpired", this.dynamicEndReason);
                            return;
                        }

                    }
                } catch (InterruptedException e) {
                    log.info("JMSSession waitIdleTimeThread interrupted, close it");
                }
            }
        });
        this.waitIdleTimeThread.start();
    }

    @Override
    public void close() {
        if (!this.beginClose()) {
            return;
        }
        this.closeJmsSessionResources();
    }

    private void closeJmsSessionResources() {
        if (this.getController() != null) {
            this.getController().cancelAllDialogs();
        }
        this.closeConsoles();
        try {
            this.replayHandler.release();
            this.finishedJmsSession();
            this.closeGateway();
            if (!this.closed) {
                this.recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType.AssetConnectFinished, "connect_disconnect");
            }

        } finally {
            super.closeSessionResources();
        }
    }

    public void close(String message, String reason, Object... args) {
        if (!this.beginClose()) {
            return;
        }
        SessionManager.setContext(this.getWebToken());
        try {
            this.getPacketIO().sendPacket("session_close", sessionClosePacketData(reason, args));

            var dialog = new Dialog(MessageUtils.get("SessionFinished"));
            dialog.setBody(MessageUtils.get(message, args));
            this.getController().showDialog(dialog);

            this.recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType.AssetConnectFinished, reason);
            this.closed = true;
        } finally {
            this.closeJmsSessionResources();
        }
    }

    private void finishedJmsSession() {
        var req = ServiceOuterClass.SessionFinishRequest
                .newBuilder()
                .setId(this.jmsSession.getId())
                .setDateEnd(Instant.now().getEpochSecond())
                .build();
        var resp = this.serviceBlockingStub.finishSession(req);
        if (!resp.getStatus().getOk()) {
            throw new SessionException(this.getUsername(), resp.getStatus().getErr());
        }
    }


    private void closeGateway() {
        if (this.gatewayId == null) {
            return;
        }

        var req = ServiceOuterClass.ForwardDeleteRequest
                .newBuilder()
                .setId(this.gatewayId)
                .build();
        var resp = this.serviceBlockingStub.deleteForward(req);
        if (!resp.getStatus().getOk()) {
            log.error("close gateway error: {}", resp.getStatus().getErr());
        }
    }


    @Override
    public SQLQueryResult withAudit(String command, QueryAuditFunction queryAuditFunction) throws SQLException, CommandRejectException {
        synchronized (this) {
            this.refreshLastActiveTime();
        }
        if (this.locked) {
            throw new CommandRejectException(MessageUtils.get("SessionLockedError"));
        }

        CommandRecord commandRecord = new CommandRecord(command);
        Throwable primaryFailure = null;

        try {
            this.replayHandler.writeInput(commandRecord.getInput());

            var result = queryAuditFunction.run();
            commandRecord.setOutput(result);

            commandRecord.setCmdAclId(result.getAclResult().getCmdAclId());
            commandRecord.setCmdGroupId(result.getAclResult().getCmdGroupId());
            commandRecord.setRiskLevel(result.getAclResult().getRiskLevel());

            this.replayHandler.writeOutput(result.getOutput());
            return result;

        } catch (SQLException | RuntimeException e) {
            primaryFailure = e;
            commandRecord.setError(e.getMessage());
            this.writeReplayFailure(e.getMessage(), e);
            throw e;
        } finally {
            try {
                this.commandHandler.recordCommand(commandRecord);
            } catch (RuntimeException auditFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(auditFailure);
                } else {
                    throw auditFailure;
                }
            }
        }
    }

    @Override
    public List<Map<String, Object>> withMetadataQueryAudit(
            String command,
            MetadataQueryAuditFunction queryAuditFunction
    ) throws SQLException {
        CommandRecord commandRecord = new CommandRecord(command);
        Throwable primaryFailure = null;

        try {
            this.replayHandler.writeInput(commandRecord.getInput());

            var rows = queryAuditFunction.run();
            var output = String.format("Metadata query OK, %d rows discovered", rows.size());
            commandRecord.setOutput(output);
            this.replayHandler.writeOutput(output);
            return rows;
        } catch (SQLException | RuntimeException e) {
            primaryFailure = e;
            var output = metadataAuditErrorSummary(e);
            commandRecord.setError(output);
            this.writeReplayFailure(output, e);
            throw e;
        } finally {
            try {
                this.commandHandler.recordCommand(commandRecord);
            } catch (RuntimeException auditFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(auditFailure);
                } else {
                    throw auditFailure;
                }
            }
        }
    }

    private static String metadataAuditErrorSummary(Throwable failure) {
        SQLException sqlFailure = null;
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                sqlFailure = sqlException;
            }
        }
        var message = failure.getMessage();
        if (sqlFailure != null && sqlFailure.getMessage() != null) {
            message = sqlFailure.getMessage();
        }
        message = message == null || message.isBlank() ? "Metadata query failed" : message;
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        var summary = sqlFailure == null
                ? message
                : String.format(
                        "SQLState=%s, vendorCode=%d, message=%s",
                        sqlFailure.getSQLState() == null ? "" : sqlFailure.getSQLState(),
                        sqlFailure.getErrorCode(),
                        message
                );
        return summary.length() <= METADATA_AUDIT_ERROR_MAX_LENGTH
                ? summary
                : summary.substring(0, METADATA_AUDIT_ERROR_MAX_LENGTH);
    }

    private void writeReplayFailure(String output, Throwable primaryFailure) {
        try {
            this.replayHandler.writeOutput(output);
        } catch (RuntimeException replayFailure) {
            primaryFailure.addSuppressed(replayFailure);
        }
    }

    static final String ADMIN_TERMINATE_REASON = "admin_terminate";

    static Map<String, Object> sessionClosePacketData(String reason, Object... args) {
        if (!ADMIN_TERMINATE_REASON.equals(reason)) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", ADMIN_TERMINATE_REASON);
        if (args != null && args.length > 0 && args[0] != null) {
            String terminatedBy = String.valueOf(args[0]).trim();
            if (!terminatedBy.isEmpty()) {
                data.put("terminatedBy", terminatedBy);
            }
        }
        return data;
    }
}
