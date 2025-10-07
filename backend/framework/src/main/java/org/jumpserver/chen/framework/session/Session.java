package org.jumpserver.chen.framework.session;

import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.wisp.Common;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface Session {

    List<Common.DataMaskingRule> getDataMaskingRules();

    void refreshLastActiveTime();

    LocalDateTime getLastActiveTime();

    boolean canUpload();

    boolean canDownload();

    boolean canCopy();

    boolean canPaste();

    Path getTempPath();

    File createFile(String fileName);

    Controller getController();

    void setGatewayId(String gatewayId);

    void setRemoteAddr(String remoteAddr);

    String getRemoteAddr();

    Locale getLocale();

    void setLocale(Locale locale);

    String getDatasourceName();

    PacketIO getPacketIO();

    Datasource getDatasource();

    Map<String, Console> getConsoles();

    String getWebToken();

    void setWebToken(String webToken);

    String getUsername();

    void setAttribute(String key, Object value);

    void removeAttribute(String key);

    Object getAttribute(String key);

    void activeSession(PacketIO packetIO);

    boolean isActive();

    void close();

    void close(String message, Object... args);

    // 在有审计的情况下执行命令
    SQLQueryResult withAudit(String command, QueryAuditFunction queryAuditFunction) throws SQLException, CommandRejectException;

    void recordCommand(String command);

    void recordCommand(CommandRecord commandRecord);

    ACLResult checkACL(String command);

    ACLResult checkACL(String command, Connection connection);

    boolean enableAutoComplete();

    void setEnableAutoComplete(boolean enableAutoComplete);
}
