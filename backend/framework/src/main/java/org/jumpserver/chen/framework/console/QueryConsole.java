package org.jumpserver.chen.framework.console;

import com.alibaba.druid.sql.parser.ParserException;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
import org.jumpserver.chen.framework.console.action.SQLChunkData;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.QueryDataViewTableEditContextFactory;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.entity.response.SaveChangesPreviewResult;
import org.jumpserver.chen.framework.console.entity.response.SaveChangesResult;
import org.jumpserver.chen.framework.console.state.QueryConsoleState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.console.transaction.QueryTransactionProbeResult;
import org.jumpserver.chen.framework.console.transaction.QueryTransactionStateInspector;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.edit.ConnectionOwnership;
import org.jumpserver.chen.framework.datasource.edit.SaveExecutionContext;
import org.jumpserver.chen.framework.datasource.edit.ServiceManagedSaveExecutionContext;
import org.jumpserver.chen.framework.datasource.edit.TableChangesPreviewService;
import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;
import org.jumpserver.chen.framework.datasource.edit.UserManagedSaveExecutionContext;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.DialogHandle;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class QueryConsole extends AbstractConsole {
    private static final String PACKET_SAVE_CHANGES_PREVIEW_RESULT = "save_changes_preview_result";
    private static final String PACKET_SAVE_CHANGES_RESULT = "save_changes_result";
    private static final long WARNING_DIALOG_TIMEOUT_SECONDS = 300;
    static final String QUERY_CONSOLE_CONNECTION_UNAVAILABLE = "QUERY_CONSOLE_CONNECTION_UNAVAILABLE";
    static final String QUERY_TRANSACTION_MANUAL_IDLE = "QUERY_TRANSACTION_MANUAL_IDLE";
    static final String QUERY_TRANSACTION_FAILED = "QUERY_TRANSACTION_FAILED";
    static final String QUERY_TRANSACTION_STATE_UNKNOWN = "QUERY_TRANSACTION_STATE_UNKNOWN";
    static final String QUERY_TRANSACTION_PROBE_FAILED = "QUERY_TRANSACTION_PROBE_FAILED";
    static final String QUERY_INSERT_NOT_SUPPORTED = "QUERY_INSERT_NOT_SUPPORTED";
    static final String QUERY_DELETE_NOT_SUPPORTED = "QUERY_DELETE_NOT_SUPPORTED";
    static final String CONSOLE_DATA_VIEW_EDIT_NOT_SUPPORTED = "CONSOLE_DATA_VIEW_EDIT_NOT_SUPPORTED";
    private static final String EXECUTION_STATUS_RUNNING = "running";
    private static final String EXECUTION_STATUS_SUCCESS = "success";
    private static final String EXECUTION_STATUS_ERROR = "error";
    private static final String EXECUTION_STATUS_CANCELLED = "cancelled";

    private final Datasource datasource;
    private final boolean consoleMode;
    private final int titleSequence;
    private final TableChangesPreviewService tableChangesPreviewService = new TableChangesPreviewService();
    private final TableChangesSaveService tableChangesSaveService = new TableChangesSaveService();
    private final QueryDataViewTableEditContextFactory tableEditContextFactory = new QueryDataViewTableEditContextFactory();
    // Single lifecycle lock: beginExecution() admits work before it can access the connection,
    // and close() waits for admitted work to finish. Reentrancy lets the admitted handler call
    // getConnection() throughout its execution without observing closed=true midway through.
    private final ReentrantLock executionLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Connection conn;
    // Driver-specific transaction probe created alongside the physical connection. The driver's
    // cached autoCommit flag does not reflect explicit BEGIN/START TRANSACTION on MySQL, Oracle,
    // SQL Server, Dameng or DB2, so the server-side probe is the real source of truth for whether
    // a user transaction is open before a DataView save commits or stages behind a savepoint.
    private volatile QueryTransactionStateInspector transactionStateInspector;
    private volatile ActiveExecution currentExecution;
    private StateManager<QueryConsoleState> stateManager;
    private final Map<String, DataView> dataViews = new LinkedHashMap<>();
    // Manual context changes remain restricted to values returned by the current server-side actuator.
    private volatile Map<String, String> allowedContexts = Map.of();

    private static final Gson GSON = new Gson();

    public QueryConsole(Datasource datasource, WebSocketSession ws, ConsoleContext context) {
        this(datasource, ws, context, false);
    }

    public QueryConsole(
            Datasource datasource,
            WebSocketSession ws,
            ConsoleContext context,
            boolean consoleMode
    ) {
        super(datasource, ws, context);
        this.consoleMode = consoleMode;
        this.titleSequence = generateConsoleName(consoleMode);
        this.setTitle(String.format(
                MessageUtils.get(consoleMode ? "Console" : "Query") + "-%d",
                this.titleSequence
        ));
        this.datasource = datasource;
    }

    private static int generateConsoleName(boolean consoleMode) {
        int num = 1;
        var consoles = SessionManager
                .getCurrentSession()
                .getConsoles();

        for (var console : consoles.values()) {
            if (console instanceof QueryConsole queryConsole && queryConsole.consoleMode == consoleMode) {
                num = Math.max(num, queryConsole.titleSequence + 1);
            }
        }
        return num;
    }

    @Override
    public void onInit(Connect connect) {
        if (!this.beginExecution()) {
            return;
        }
        try {
            super.onInit(connect);
            this.onConnect(connect);
        } finally {
            this.executionLock.unlock();
        }
    }

    public void onConnect(Connect connect) {
        this.getConsoleLogger().info("Websocket" + MessageUtils.get("Connected"));

        this.stateManager = new StateManager<>(new QueryConsoleState(this.getTitle())
                , this.getPacketIO());
        this.getState().setLoading(true);
        this.stateManager.commit();

        var context = this.getInitialContext();
        try {
            var currentContext = this.getSqlActuator().getCurrentSchema();

            if (StringUtils.isEmpty(currentContext) && !StringUtils.isEmpty(context)) {
                this.getSqlActuator().changeSchema(context);
                this.getState().setCurrentContext(context);
            } else {
                if (!StringUtils.isEmpty(context) && !currentContext.equals(context)) {
                    this.getSqlActuator().changeSchema(context);
                    this.getState().setCurrentContext(context);
                }
            }

            var schemas = this.getSqlActuator().getSchemas();
            this.replaceAllowedContexts(schemas);
            this.getState().setContexts(schemas);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("ConnectError") + ": %s", e.getMessage());
            throw new IllegalStateException("Failed to initialize query console", e);
        }

        this.getState().setLoading(false);
        this.stateManager.commit();

    }

    String getInitialContext() {
        var contextKey = this.getDatasource().getConnectionManager().getContextKey();
        return StringUtils.equals(contextKey, "database")
                ? this.getContext().database()
                : this.getContext().schema();
    }

    private Connection getConnection() {
        if (!this.executionLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Connection access requires admitted execution");
        }
        return this.getOrCreateConnection();
    }

    private Connection getOrCreateConnection() {
        if (this.conn != null) {
            return this.conn;
        }

        Connection candidate = null;
        try {
            var connectionManager = this.getDatasource().getConnectionManager();
            String currentContext = this.currentConnectionContext();
            if (StringUtils.isNotBlank(currentContext) &&
                    StringUtils.equals(
                            connectionManager.getContextKey(),
                            connectionManager.getDatabaseContextKey()
                    )) {
                connectionManager.setDatabaseContext(currentContext);
            }

            candidate = connectionManager.getPhysicalConnection();
            if (StringUtils.isNotBlank(currentContext)) {
                connectionManager.getSqlActuator()
                        .withConnection(candidate)
                        .changeSchema(currentContext);
            }
            QueryTransactionStateInspector candidateInspector = QueryTransactionStateInspector.create(
                    this.getDatasource().getDruidDbType(),
                    candidate
            );

            this.conn = candidate;
            this.transactionStateInspector = candidateInspector;
            return candidate;
        } catch (SQLException e) {
            closeQuietly(candidate);
            throw new QueryConsoleConnectionUnavailableException(e);
        } catch (RuntimeException e) {
            closeQuietly(candidate);
            throw e;
        }
    }

    private String currentConnectionContext() {
        if (this.stateManager == null) {
            return null;
        }
        return this.getState().getCurrentContext();
    }

    public String getCurrentContext() {
        return this.stateManager == null ? "" : StringUtils.defaultString(this.getState().getCurrentContext());
    }

    @Override
    public void handle(Packet packet) {
        if (this.isCancelPacket(packet)) {
            this.onCancel();
            return;
        }
        if (!this.beginExecution()) {
            return;
        }
        try {
            this.handleSerialPacket(packet);
        } catch (QueryConsoleConnectionUnavailableException e) {
            log.warn("query console connection unavailable", e);
            this.getMessager().send(Message.error(
                    MessageUtils.get("FetchError"),
                    QUERY_CONSOLE_CONNECTION_UNAVAILABLE
            ));
        } finally {
            this.executionLock.unlock();
        }
    }

    private void handleSerialPacket(Packet packet) {

        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case "close_data_view" -> {
                var reference = (String) packet.getData();
                var dataView = this.findDataView(reference);
                if (dataView != null) {
                    this.dataViews.remove(dataView.getId());
                }
                log.info("close data view {}", reference);
            }

            case Packet.TYPE_QUERY_CONSOLE_ACTION -> {
                var action = GSON.fromJson(GSON.toJson(packet.getData()), QueryConsoleAction.class);
                this.onAction(action);

            }
            case Packet.TYPE_DATA_VIEW_ACTION -> {
                var action = GSON.fromJson(GSON.toJson(packet.getData()), DataViewAction.class);
                this.onDataViewAction(action);
            }
            default -> log.warn("Unknown packet type {}", packet.getType());
        }
    }

    private boolean isCancelPacket(Packet packet) {
        if (!StringUtils.equals(packet.getType(), Packet.TYPE_QUERY_CONSOLE_ACTION)) {
            return false;
        }
        var action = GSON.fromJson(GSON.toJson(packet.getData()), QueryConsoleAction.class);
        return StringUtils.equals(action.getAction(), QueryConsoleAction.ACTION_CANCEL);
    }

    private boolean beginExecution() {
        if (this.closed.get()) {
            return false;
        }
        this.executionLock.lock();
        if (this.closed.get()) {
            this.executionLock.unlock();
            return false;
        }
        return true;
    }


    private void onAction(QueryConsoleAction action) {
        switch (action.getAction()) {
            case QueryConsoleAction.ACTION_RUN_SQL -> {
                this.getState().setInQuery(true);
                this.stateManager.commit();

                var sql = (String) action.getData();
                this.onSQL(sql);

                this.getState().setInQuery(false);
                this.stateManager.commit();
            }
            case QueryConsoleAction.ACTION_RUN_SQL_CHUNK -> {
                this.handleSQLChunk(action);
            }
            case QueryConsoleAction.ACTION_RUN_SQL_COMPLETE -> {
                this.handleSQLComplete();
            }

            case QueryConsoleAction.ACTION_RUN_SQL_FILE -> {
                this.getState().setInQuery(true);
                this.stateManager.commit();

                var sqlFile = (String) action.getData();
                this.onSQLFile(sqlFile);

                this.getState().setInQuery(false);
                this.stateManager.commit();
            }

            case QueryConsoleAction.ACTION_CHANGE_CURRENT_CONTEXT -> {
                var schema = (String) action.getData();
                this.onManualChangeContext(schema);
            }
        }
    }

    private final Map<Integer, String> sqlChunks = new HashMap<>();
    private int expectedChunks = -1;

    private void handleSQLChunk(QueryConsoleAction action) {
        SQLChunkData data;
        try {
            data = GSON.fromJson(GSON.toJson(action.getData()), SQLChunkData.class);
        } catch (JsonParseException e) {
            this.getConsoleLogger().error("invalid sql chunk");
            this.resetSQLChunks();
            return;
        }

        if (data == null
                || data.getChunk() == null
                || data.getIndex() == null
                || data.getTotal() == null
                || data.getTotal() <= 0) {
            this.getConsoleLogger().error("invalid sql chunk");
            this.resetSQLChunks();
            return;
        }
        var chunk = data.getChunk();
        var index = data.getIndex();
        var total = data.getTotal();
        if (expectedChunks == -1) {
            expectedChunks = total;
        }
        if (total != expectedChunks || index < 0 || index >= expectedChunks) {
            this.getConsoleLogger().error("invalid sql chunk");
            this.resetSQLChunks();
            return;
        }
        sqlChunks.putIfAbsent(index, chunk);
    }

    /**
     * 处理分段 SQL 接收完成
     */
    private void handleSQLComplete() {
        try {
            if (expectedChunks <= 0 || sqlChunks.size() != expectedChunks) {
                this.getConsoleLogger().error("read sql message timeout!！");
                return;
            }

            // 按照索引顺序合并所有分段
            StringBuilder sqlBuilder = new StringBuilder();
            for (int i = 0; i < expectedChunks; i++) {
                String chunk = sqlChunks.get(i);
                if (chunk == null) {
                    this.getConsoleLogger().error("read sql message timeout!！");
                    return;
                }
                sqlBuilder.append(chunk);
            }

            var sql = sqlBuilder.toString();

            // 执行完整 SQL
            this.getState().setInQuery(true);
            this.stateManager.commit();

            this.onSQL(sql);

        } finally {
            this.resetSQLChunks();
            this.getState().setInQuery(false);
            this.stateManager.commit();
        }
    }

    private void resetSQLChunks() {
        this.sqlChunks.clear();
        this.expectedChunks = -1;
    }

    private void onDataViewAction(DataViewAction action) {
        if (this.consoleMode && DataViewAction.ACTION_SAVE_CHANGES_PREVIEW.equals(action.getAction())) {
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, Map.of(
                    "success", false,
                    "allowed", false,
                    "reason", CONSOLE_DATA_VIEW_EDIT_NOT_SUPPORTED,
                    "dataView", action.getDataView()
            ));
            return;
        }
        if (this.consoleMode && DataViewAction.ACTION_SAVE_CHANGES.equals(action.getAction())) {
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_RESULT, Map.of(
                    "success", false,
                    "allowed", false,
                    "reason", CONSOLE_DATA_VIEW_EDIT_NOT_SUPPORTED,
                    "dataView", action.getDataView()
            ));
            return;
        }
        var dataView = this.findDataView(action.getDataView());
        if (dataView == null) {
            log.error("data view {} not found", action.getDataView());
            return;
        }
        if (DataViewAction.ACTION_SAVE_CHANGES_PREVIEW.equals(action.getAction())) {
            var request = GSON.fromJson(GSON.toJson(action.getData()), SaveChangesRequest.class);
            String unsupportedReason = unsupportedQueryMutation(request);
            if (unsupportedReason != null) {
                this.getPacketIO().sendPacket(
                        PACKET_SAVE_CHANGES_PREVIEW_RESULT,
                        this.rejectedPreview(dataView, unsupportedReason)
                );
                return;
            }
            try {
                var context = this.tableEditContextFactory.create(dataView, this.getDatasource().getDruidDbType());
                var result = this.tableChangesPreviewService.preview(context, dataView.getTitle(), request);
                this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, result);
            } catch (IllegalArgumentException e) {
                this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, this.rejectedPreview(dataView, e.getMessage()));
            }
            return;
        }
        if (DataViewAction.ACTION_SAVE_CHANGES.equals(action.getAction())) {
            var request = GSON.fromJson(GSON.toJson(action.getData()), SaveChangesRequest.class);
            SaveChangesResult result = this.saveQueryChanges(dataView, dataView.getTitle(), request);
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_RESULT, result);
            return;
        }
        try {
            dataView.getStateManager().getState().setLoading(true);
            dataView.getStateManager().commit();

            dataView.doAction(action);

            this.getPacketIO().sendPacket(
                    "update_data_view",
                    new UpdateDataView(dataView.getId(), dataView.getTitle(), dataView.getData())
            );

        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("FetchError"), e.getMessage()));
        } finally {
            dataView.getStateManager().getState().setLoading(false);
            dataView.getStateManager().commit();
        }
    }

    private SaveChangesResult saveQueryChanges(
            DataView dataView,
            String actionDataView,
            SaveChangesRequest request
    ) {
        String unsupportedReason = unsupportedQueryMutation(request);
        if (unsupportedReason != null) {
            return this.rejectedSave(dataView, unsupportedReason);
        }

        // Probe the driver-side transaction state before touching the connection. The cached
        // autoCommit flag is unreliable on MySQL/Oracle/SQL Server/Dameng/DB2 (it does not flip on
        // explicit BEGIN), so the server-side probe is the source of truth:
        //   AUTO_COMMIT            -> no user tx open; service-managed transaction committed for the user
        //   TRANSACTION_ACTIVE     -> user owns the tx; batch staged behind a savepoint, outer tx never committed
        //   MANUAL_COMMIT_IDLE     -> reject; Chen does not start a user transaction implicitly
        //   TRANSACTION_FAILED     -> reject; the user must rollback the failed tx first
        //   UNKNOWN / probeFailed  -> fail closed; never risk committing or staging on an uncertain connection
        Connection connection = this.getConnection();
        QueryTransactionStateInspector inspector = this.transactionStateInspector;
        if (inspector == null) {
            return this.rejectedSave(dataView, QUERY_CONSOLE_CONNECTION_UNAVAILABLE);
        }
        QueryTransactionProbeResult probeResult = inspector.probeNow();
        if (probeResult.probeFailed()) {
            return this.rejectedSave(dataView, QUERY_TRANSACTION_PROBE_FAILED);
        }

        SaveExecutionContext executionContext;
        switch (probeResult.state()) {
            case AUTO_COMMIT -> executionContext = new ServiceManagedSaveExecutionContext(
                    connection,
                    ConnectionOwnership.QUERY_CONSOLE
            );
            case TRANSACTION_ACTIVE -> executionContext = new UserManagedSaveExecutionContext(connection);
            case MANUAL_COMMIT_IDLE -> {
                return this.rejectedSave(dataView, QUERY_TRANSACTION_MANUAL_IDLE);
            }
            case TRANSACTION_FAILED -> {
                return this.rejectedSave(dataView, QUERY_TRANSACTION_FAILED);
            }
            case UNKNOWN -> {
                return this.rejectedSave(dataView, QUERY_TRANSACTION_STATE_UNKNOWN);
            }
            default -> {
                return this.rejectedSave(dataView, QUERY_TRANSACTION_STATE_UNKNOWN);
            }
        }

        try {
            var context = this.tableEditContextFactory.create(
                    dataView,
                    this.getDatasource().getDruidDbType()
            );
            SaveChangesResult result = this.tableChangesSaveService.save(
                    context,
                    actionDataView,
                    request,
                    executionContext,
                    SessionManager.getCurrentSession()
            );
            if (result.isConnectionInvalidated()) {
                this.invalidateConnection(result.getReason());
            }
            return result;
        } catch (IllegalArgumentException e) {
            return this.rejectedSave(dataView, e.getMessage());
        }
    }

    private void invalidateConnection(String reason) {
        closeQuietly(this.detachConnection());
        log.warn("QueryConsole connection invalidated, reason={}", reason);
    }

    private Connection detachConnection() {
        Connection old = this.conn;
        this.conn = null;
        this.transactionStateInspector = null;
        return old;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            log.warn("close query console connection failed", e);
        }
    }

    private static String unsupportedQueryMutation(SaveChangesRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getInsertRows() != null && !request.getInsertRows().isEmpty()) {
            return QUERY_INSERT_NOT_SUPPORTED;
        }
        if (request.getDeleteRows() != null && !request.getDeleteRows().isEmpty()) {
            return QUERY_DELETE_NOT_SUPPORTED;
        }
        return null;
    }

    private SaveChangesPreviewResult rejectedPreview(DataView dataView, String reason) {
        SaveChangesPreviewResult result = new SaveChangesPreviewResult();
        result.setSuccess(false);
        result.setAllowed(false);
        result.setReason(reason);
        result.setDataView(dataView.getId());
        return result;
    }

    private SaveChangesResult rejectedSave(DataView dataView, String reason) {
        SaveChangesResult result = new SaveChangesResult();
        result.setSuccess(false);
        result.setAllowed(false);
        result.setReason(reason);
        result.setDataView(dataView.getId());
        return result;
    }

    public void onCancel() {
        this.getState().setExecutionStatus(EXECUTION_STATUS_CANCELLED);
        try {
            var execution = this.currentExecution;
            if (execution != null) {
                execution.cancelAction().cancel();
                this.getConsoleLogger().warn("cancel query: %s", execution.sql());
            }
        } catch (SQLException | RuntimeException e) {
            log.error("cancel failed ", e);
        }
    }

    public void onManualChangeContext(String context) {
        if (!this.isAllowedContext(context)) {
            return;
        }
        var allowedContext = this.allowedContexts.get(context);
        if (StringUtils.equals(this.getState().getCurrentContext(), allowedContext)) {
            return;
        }
        try {
            this.getState().setEditorLoading(true);
            this.stateManager.commit();

            this.getSqlActuator().changeSchema(allowedContext);
            var connectionManager = this.getDatasource().getConnectionManager();
            // 只有当前 UI 上下文本身就是 JDBC database 时，才同步连接池上下文，避免 PostgreSQL schema 被当 database。
            if (StringUtils.isNotBlank(allowedContext) &&
                    StringUtils.equals(connectionManager.getContextKey(), connectionManager.getDatabaseContextKey())) {
                connectionManager.setDatabaseContext(allowedContext);
            }
            this.getState().setCurrentContext(allowedContext);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("ChangeContextError") + ": %s", e.getMessage());
        } finally {
            this.getState().setEditorLoading(false);
            this.stateManager.commit();
        }

    }

    void replaceAllowedContexts(List<String> contexts) {
        var canonicalContexts = new LinkedHashMap<String, String>();
        if (contexts != null) {
            for (String context : contexts) {
                if (StringUtils.isNotBlank(context)) {
                    canonicalContexts.putIfAbsent(context, context);
                }
            }
        }
        this.allowedContexts = Collections.unmodifiableMap(canonicalContexts);
    }

    boolean isAllowedContext(String context) {
        return StringUtils.isNotBlank(context) && this.allowedContexts.containsKey(context);
    }

    public void onSQLFile(String filename) {
        var filePath = this.resolveSQLFileInSessionTemp(filename);
        if (filePath == null) {
            log.warn("Rejected invalid SQL file name");
            return;
        }
        if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_found"), filename);
            return;
        }
        if (!Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_file"), filename);
            return;
        }
        if (!Files.isReadable(filePath)) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_readable"), filename);
            return;
        }

        var shouldDelete = false;
        try {
            shouldDelete = true;
            String sql;
            try {
                try (var inputStream = Files.newInputStream(
                        filePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException | SecurityException e) {
                this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_read_error"), e.getMessage());
                return;
            }
            this.onSQL(sql);
        } finally {
            if (shouldDelete) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException | SecurityException e) {
                    log.warn("Failed to delete session SQL file", e);
                }
            }
        }
    }

    private Path resolveSQLFileInSessionTemp(String filename) {
        if (StringUtils.isBlank(filename)
                || StringUtils.equalsAny(filename, ".", "..")
                || filename.contains("..")
                || filename.contains("/")
                || filename.contains("\\")) {
            return null;
        }

        try {
            var requested = Path.of(filename);
            if (requested.isAbsolute()
                    || requested.getNameCount() != 1
                    || !requested.equals(requested.getFileName())) {
                return null;
            }

            var basePath = SessionManager.getCurrentSession().getTempPath()
                    .toAbsolutePath()
                    .normalize();
            var resolvedPath = basePath.resolve(requested.getFileName()).normalize();
            return resolvedPath.startsWith(basePath) ? resolvedPath : null;
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    public void onSQL(String sql) {
        this.getState().setInQuery(true);
        this.getState().setExecutionStatus(EXECUTION_STATUS_RUNNING);
        this.stateManager.commit();
        var session = SessionManager.getCurrentSession();

        try {
            if (this.consoleMode) {
                this.runRawConsoleSQL(sql, session);
            } else {
                this.runQuerySQL(sql, session);
            }
            this.ensureCurrentSchema();
        } catch (ParserException e) {
            this.getState().setExecutionStatus(EXECUTION_STATUS_ERROR);
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("ParseError"), e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("ParseError"), e.getMessage()));
            this.sendSQLError("parse", MessageUtils.get("ParseError"), e.getMessage(), sql, null);
        } catch (SQLException e) {
            if (!StringUtils.equals(this.getState().getExecutionStatus(), EXECUTION_STATUS_CANCELLED)) {
                this.getState().setExecutionStatus(EXECUTION_STATUS_ERROR);
                this.getConsoleLogger().error("%s: %s", MessageUtils.get("ExecuteError"), e.getMessage());
                this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("ExecuteError"), e.getMessage()));
                this.sendSQLError("execute", MessageUtils.get("ExecuteError"), e.getMessage(), sql, e);
            }
        } finally {
            if (StringUtils.equals(this.getState().getExecutionStatus(), EXECUTION_STATUS_RUNNING)) {
                this.getState().setExecutionStatus(EXECUTION_STATUS_SUCCESS);
            }
            this.getState().setInQuery(false);
            this.getState().setCanCancel(false);
            this.stateManager.commit();
        }
    }

    private void runQuerySQL(String sql, Session session) throws SQLException {
        var statements = this.getSqlActuator().parseSQL(SQL.of(sql));
        var clearOthers = true;
        for (String statement : statements) {
            var aclResult = session.checkACL(statement, this.getConnection());
            if (!this.canExecuteStatement(session, statement, aclResult)) {
                break;
            }
            var dataView = this.runSingleSQL(statement, aclResult);
            if (!dataView.isHasTable()) {
                this.logAffectedRows(dataView);
            } else {
                this.sendDataView(dataView, clearOthers);
                clearOthers = false;
            }
        }
    }

    private void runRawConsoleSQL(String sql, Session session) throws SQLException {
        ConsoleStatementBoundaryScanner.requireSingleStatement(sql);
        Connection connection = this.getConnection();
        ACLResult aclResult = session.checkACL(sql, connection);
        if (!this.canExecuteStatement(session, sql, aclResult)) {
            return;
        }

        DataView dataView = new DataView(
                UUID.randomUUID().toString(), sql, this.getPacketIO(), this.getConsoleLogger()
        );
        dataView.setSql(sql);
        dataView.setLoadDataInterface((ignored) -> this.executeRawConsoleSQL(sql, aclResult, connection));
        dataView.loadData();
        if (!dataView.isHasTable()) {
            this.logAffectedRows(dataView);
        } else {
            this.sendDataView(dataView, true);
        }
    }

    private SQLQueryResult executeRawConsoleSQL(String sql, ACLResult aclResult, Connection connection)
            throws SQLException {
        SQLActuator actuator = this.datasource.getConnectionManager().getSqlActuator().withConnection(connection);
        SQLExecutePlan plan = actuator.createPlan(SQL.of(sql));
        plan.setAclResult(aclResult);
        Statement statement = plan.createStatement();
        ActiveExecution execution = new ActiveExecution(sql, statement::cancel);
        this.currentExecution = execution;
        this.getState().setCanCancel(true);
        this.stateManager.commit();
        try {
            this.getConsoleLogger().info("execute raw sql: %s", sql);
            SQLQueryResult result = actuator.executeRawWithAudit(plan);
            this.getConsoleLogger().success(result);
            return result;
        } finally {
            if (this.currentExecution == execution) {
                this.currentExecution = null;
            }
            this.getState().setCanCancel(false);
            this.stateManager.commit();
        }
    }

    private void logAffectedRows(DataView dataView) {
        this.getConsoleLogger().success("%s , %s: %d",
                MessageUtils.get("ExecuteSuccess"),
                MessageUtils.get("AffectedRows"), dataView.getUpdateCount());
    }

    private void sendSQLError(String kind, String title, String message, String sql, SQLException exception) {
        var error = new LinkedHashMap<String, Object>();
        error.put("kind", kind);
        error.put("title", StringUtils.defaultString(title));
        error.put("message", StringUtils.defaultString(message));
        error.put("sql", StringUtils.defaultString(sql));
        error.put("timestamp", System.currentTimeMillis());
        if (exception != null) {
            if (StringUtils.isNotBlank(exception.getSQLState())) {
                error.put("sqlState", exception.getSQLState());
            }
            error.put("vendorCode", exception.getErrorCode());
        }
        this.getPacketIO().sendPacket("sql_error", error);
    }

    private boolean canExecuteStatement(Session session, String sql, ACLResult aclResult) {
        if (aclResult == null) {
            return true;
        }
        if (aclResult.getRiskLevel() == Common.RiskLevel.Reject ||
                aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject ||
                aclResult.getRiskLevel() == Common.RiskLevel.ReviewCancel) {
            this.getState().setExecutionStatus(
                    aclResult.getRiskLevel() == Common.RiskLevel.ReviewCancel
                            ? EXECUTION_STATUS_CANCELLED
                            : EXECUTION_STATUS_ERROR
            );
            this.getConsoleLogger().error("%s", MessageUtils.get("ACLRejectError"));
            CommandRecord commandRecord = new CommandRecord(sql);
            commandRecord.setRiskLevel(aclResult.getRiskLevel());
            session.recordCommand(commandRecord);
            return false;
        }
        return !aclResult.isNotify() || this.confirmStatementWarning(session);
    }

    private boolean confirmStatementWarning(Session session) {
        var dialog = new Dialog(MessageUtils.get("Warning"));
        dialog.setBody(MessageUtils.get("CommandWarningDialogMessage"));
        var countDownLatch = new CountDownLatch(1);
        AtomicBoolean hasNext = new AtomicBoolean(true);

        dialog.addButton(new Button(MessageUtils.get("Submit"), "submit", countDownLatch::countDown));
        dialog.addButton(new Button(MessageUtils.get("Cancel"), "cancel", () -> {
            hasNext.set(false);
            this.getState().setExecutionStatus(EXECUTION_STATUS_CANCELLED);
            countDownLatch.countDown();
            this.getConsoleLogger().warn(MessageUtils.get("ExecutionCanceled"));
        }));

        var controller = session.getController();
        DialogHandle dialogHandle = controller.showDialog(dialog, () -> {
            hasNext.set(false);
            countDownLatch.countDown();
        });

        try {
            if (!countDownLatch.await(WARNING_DIALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                hasNext.set(false);
                this.getState().setExecutionStatus(EXECUTION_STATUS_CANCELLED);
                dialogHandle.cancel();
                this.getConsoleLogger().warn(MessageUtils.get("ExecutionCanceled"));
            }
            return hasNext.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.getState().setExecutionStatus(EXECUTION_STATUS_ERROR);
            this.getConsoleLogger().error("获取结果失败!");
            return false;
        } finally {
            dialogHandle.close();
        }
    }

    private SQLActuator getSqlActuator() {
        return this.getDatasource()
                .getConnectionManager()
                .getSqlActuator()
                .withConnection(this.getConnection());
    }


    private QueryConsoleState getState() {
        return this.stateManager.getState();
    }

    private void ensureCurrentSchema() {
        try {
            var schema = this.getSqlActuator().getCurrentSchema();

            if (!StringUtils.equals(schema, this.getState().getCurrentContext())) {
                this.getState().setCurrentContext(schema);
                this.stateManager.commit();
            }
        } catch (SQLException e) {
            log.error("get current schema failed {}", e.getMessage(), e);
        }
    }

    private DataView runSingleSQL(String sql, ACLResult aclResult) throws SQLException {

        String sourceSQL = sql;
        DataView dataView = new DataView(
                UUID.randomUUID().toString(),
                sourceSQL,
                this.getPacketIO(),
                this.getConsoleLogger()
        );
        dataView.setSql(sourceSQL);

        dataView.setLoadDataInterface((sqlQueryParams) -> {
            sqlQueryParams.setTimeout(this.getState().getTimeout());

            SQLExecutePlan plan = this.datasource
                    .getConnectionManager()
                    .getSqlActuator()
                    .withConnection(this.getConnection())
                    .createPlan(SQL.of(sourceSQL));
            plan.setAclResult(aclResult);
            plan.setSqlQueryParams(sqlQueryParams);
            ActiveExecution execution = new ActiveExecution(sourceSQL, plan::cancel);
            this.currentExecution = execution;
            this.getState().setCanCancel(true);
            this.stateManager.commit();

            try {
                plan.generateTargetSQL();
                this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());
                var result = plan.executeWithAudit();
                this.getConsoleLogger().success(result);
                return result;
            } finally {
                if (this.currentExecution == execution) {
                    this.currentExecution = null;
                }
                this.getState().setCanCancel(false);
                this.stateManager.commit();
            }
        });


        dataView.loadData();

        return dataView;
    }


    private void sendDataView(DataView dataView, boolean clearOthers) {
        if (this.consoleMode) {
            this.getPacketIO().sendPacket("console_result", Map.of(
                    "id", dataView.getId(),
                    "title", dataView.getTitle(),
                    "data", dataView.getData(),
                    "state", dataView.getStateManager().getState()
            ));
            return;
        }

        if (clearOthers) {
            var forDeleteDataViewIds = new ArrayList<String>();
            for (var entry : this.dataViews.entrySet()) {
                if (!entry.getValue().getStateManager().getState().isPinned()) {
                    forDeleteDataViewIds.add(entry.getKey());
                }
            }
            forDeleteDataViewIds.forEach(this.dataViews.keySet()::remove);
            this.getPacketIO().sendPacket("close_data_view", forDeleteDataViewIds);
        }

        this.getPacketIO().sendPacket(
                "new_data_view",
                Map.of("id", dataView.getId(), "title", dataView.getTitle())
        );

        this.dataViews.put(dataView.getId(), dataView);
        this.getPacketIO().sendPacket(
                "update_data_view",
                new UpdateDataView(dataView.getId(), dataView.getTitle(), dataView.getData())
        );
        dataView.getStateManager().commit();
    }

    private DataView findDataView(String reference) {
        var dataView = this.dataViews.get(reference);
        if (dataView != null) {
            return dataView;
        }
        DataView matched = null;
        for (var candidate : this.dataViews.values()) {
            if (StringUtils.equals(candidate.getTitle(), reference)) {
                matched = candidate;
            }
        }
        return matched;
    }


    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        var currentSession = SessionManager.getCurrentSession();
        if (currentSession == null && this.getPacketIO().getWsSession().getAttributes() != null) {
            Object token = this.getPacketIO().getWsSession().getAttributes().get("token");
            if (token instanceof String sessionToken) {
                currentSession = SessionManager.getSession(sessionToken);
            }
        }
        if (currentSession != null && currentSession.getController() != null) {
            currentSession.getController().cancelDialogs(this.getPacketIO().getWsSession().getId());
        }

        var execution = this.currentExecution;
        if (execution != null) {
            try {
                // flush
                var session = SessionManager.getCurrentSession();
                var lastCmd = execution.sql();
                var cmdRecord = new CommandRecord(lastCmd);
                cmdRecord.setError("Abnormal exit");
                if (session != null) {
                    session.recordCommand(cmdRecord);
                }
            } catch (RuntimeException e) {
                log.warn("record interrupted query failed", e);
            }
        }
        this.onCancel();

        this.executionLock.lock();
        try {
            closeQuietly(this.detachConnection());
        } finally {
            this.executionLock.unlock();
        }
        log.info("console closed");
    }

    @FunctionalInterface
    private interface ExecutionCancel {
        void cancel() throws SQLException;
    }

    private record ActiveExecution(String sql, ExecutionCancel cancelAction) {
    }
}
