package org.jumpserver.chen.framework.session.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.QueryAuditFunction;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.session.controller.impl.BaseController;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class BaseSession implements Session {

    @Setter
    @Getter
    private String webToken;
    @Getter
    private Datasource datasource;
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private PacketIO packetIO;

    @Getter
    Map<String, Console> consoles = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private Locale locale = Locale.CHINA;


    @Setter
    @Getter
    private String remoteAddr;


    @Getter
    private Controller controller;

    private boolean enableAutoComplete = true;


    public BaseSession(Datasource datasource, String remoteAddr) {
        this.datasource = datasource;
        this.remoteAddr = remoteAddr;
    }


    @Override
    public boolean canUpload() {
        return true;
    }

    @Override
    public boolean canDownload() {
        return true;
    }

    @Override
    public boolean canCopy() {
        return true;
    }

    @Override
    public boolean canPaste() {
        return true;
    }

    @Override
    public Path getTempPath() {
        var tempPath = Paths.get(String.format("data/tmp/%s", this.webToken));
        if (!tempPath.toFile().exists()) {
            tempPath.toFile().mkdirs();
        }
        return tempPath;
    }

    @Override
    public File createFile(String fileName) {
        return Paths.get(this.getTempPath().toString(), fileName).toFile();
    }

    @Override
    public void setGatewayId(String gatewayId) {
    }

    @Override
    public String getDatasourceName() {
        var info = this.datasource.getConnectInfo();
        return "%s@%s:%d".formatted(info.getUser(), info.getHost(), info.getPort());
    }

    @Override
    public String getUsername() {
        return "mock";
    }

    @Override
    public SQLQueryResult withAudit(String command, QueryAuditFunction queryAuditFunction) throws SQLException, CommandRejectException {
        return queryAuditFunction.run();
    }

    @Override
    public void recordCommand(String command) {
        CommandRecord commandRecord = new CommandRecord(command);
        this.recordCommand(commandRecord);

    }

    @Override
    public void recordCommand(CommandRecord commandRecord) {
        log.info("record command: {}", commandRecord);
    }

    @Override
    public ACLResult checkACL(String command) {
        return null;
    }

    @Override
    public ACLResult checkACL(String command, Connection connection) {
        return null;
    }

    @Override
    public boolean enableAutoComplete() {
        return this.enableAutoComplete;
    }

    @Override
    public void setEnableAutoComplete(boolean enableAutoComplete) {
        this.enableAutoComplete = enableAutoComplete;
    }


    @Override
    public void setAttribute(String key, Object value) {
        this.attrs.put(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        this.attrs.remove(key);
    }

    @Override
    public Object getAttribute(String key) {
        return this.attrs.get(key);
    }

    @Override
    public void activeSession(PacketIO packetIO) {
        this.setPacketIO(packetIO);
        this.controller = new BaseController(this.packetIO);
    }

    @Override
    public boolean isActive() {
        return this.getPacketIO() != null && this.getPacketIO().getWsSession().isOpen();
    }

    @Override
    public void close() {
        SessionManager.unregisterSession(this.getWebToken());
        this.getDatasource().close();
        this.getPacketIO().close();
        var path = this.getTempPath();
        if (path.toFile().exists()) {
            path.toFile().delete();
        }
    }

    @Override
    public void close(String message, Object... args) {
        this.close();
    }

}
