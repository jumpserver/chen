package org.jumpserver.chen.framework.console.component;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.console.entity.response.Log;
import org.jumpserver.chen.framework.ws.io.PacketIO;


@Slf4j
public class Logger {

    private static final int LOG_LEVEL_SUCCESS = 3;
    private static final int LOG_LEVEL_INFO = 2;
    private static final int LOG_LEVEL_WARN = 1;
    private static final int LOG_LEVEL_ERROR = 0;

    private final PacketIO packetIO;

    public Logger(PacketIO packetIO) {
        this.packetIO = packetIO;
    }

    public void consoleLog(int level, String message, Object... args) {
        var msg = String.format(message, args);
        var logMsg = new Log(level, msg);
        this.packetIO.sendPacket(Packet.TYPE_LOG, logMsg);
    }

    public void success(SQLQueryResult result) {
        if (result.isHasResultSet()) {
            this.success("%d rows retrieved  in %d ms (execution: %d ms, fetching: %d ms)",
                    result.getData().size(),
                    result.getTotalTimeUsed(),
                    result.getQueryTimeUsed(),
                    result.getFetchTimeUsed()
            );
        } else {
            this.success("%d rows affected in %d ms",
                    result.getUpdateCount(),
                    result.getTotalTimeUsed()
            );
        }
    }

    public void success(String message, Object... args) {
        this.consoleLog(LOG_LEVEL_SUCCESS, message, args);
    }

    public void info(String message, Object... args) {
        this.consoleLog(LOG_LEVEL_INFO, message, args);
    }

    public void warn(String message, Object... args) {
        this.consoleLog(LOG_LEVEL_WARN, message, args);
    }

    public void error(String message, Object... args) {
        this.consoleLog(LOG_LEVEL_ERROR, message, args);
    }
}
