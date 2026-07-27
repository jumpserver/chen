package org.jumpserver.chen.framework.console;

import com.alibaba.druid.sql.parser.ParserException;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
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
import org.jumpserver.chen.framework.console.transaction.QueryTransactionState;
import org.jumpserver.chen.framework.console.transaction.QueryTransactionStateTracker;
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
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class QueryConsole extends AbstractConsole {
    private static final String PACKET_SAVE_CHANGES_PREVIEW_RESULT = "save_changes_preview_result";
    private static final String PACKET_SAVE_CHANGES_RESULT = "save_changes_result";
    static final String QUERY_TRANSACTION_MANUAL_IDLE = "QUERY_TRANSACTION_MANUAL_IDLE";
    static final String QUERY_TRANSACTION_FAILED = "QUERY_TRANSACTION_FAILED";
    static final String QUERY_TRANSACTION_STATE_UNKNOWN = "QUERY_TRANSACTION_STATE_UNKNOWN";
    static final String QUERY_TRANSACTION_PROBE_FAILED = "QUERY_TRANSACTION_PROBE_FAILED";
    static final String QUERY_INSERT_NOT_SUPPORTED = "QUERY_INSERT_NOT_SUPPORTED";
    static final String QUERY_DELETE_NOT_SUPPORTED = "QUERY_DELETE_NOT_SUPPORTED";

    private final Datasource datasource;
    private final TableChangesPreviewService tableChangesPreviewService = new TableChangesPreviewService();
    private final TableChangesSaveService tableChangesSaveService = new TableChangesSaveService();
    private final QueryDataViewTableEditContextFactory tableEditContextFactory = new QueryDataViewTableEditContextFactory();
    private final Object connectionMonitor = new Object();
    // WebSocket messages are ordered by SerialExecutor; this lock is a lifecycle barrier for close().
    private final ReentrantLock executionLock = new ReentrantLock(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
    private Connection conn;
    private volatile QueryTransactionStateTracker transactionStateTracker;
    private volatile SQLExecutePlan currentPlan;
    private StateManager<QueryConsoleState> stateManager;
    private final Map<String, DataView> dataViews = new HashMap<>();
    // Manual context changes remain restricted to values returned by the current server-side actuator.
    private volatile Map<String, String> allowedContexts = Map.of();

    private static final Gson GSON = new Gson();

    public QueryConsole(Datasource datasource, WebSocketSession ws, ConsoleContext context) {
        super(datasource, ws, context);
        this.setTitle(String.format(MessageUtils.get("Query") + "-%d", generateConsoleName()));
        this.datasource = datasource;
    }

    private static int generateConsoleName() {
        int num = 1;
        var consoles = SessionManager
                .getCurrentSession()
                .getConsoles();

        for (var console : consoles.values()) {
            if (console instanceof QueryConsole) {
                ++num;
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

    Connection getConnection() {
        synchronized (this.connectionMonitor) {
            if (this.closed.get()) {
                throw new IllegalStateException("Query console is closed");
            }
            if (this.conn == null) {
                try {
                    this.conn = this.getDatasource().getConnectionManager().getPhysicalConnection();
                    this.transactionStateTracker = QueryTransactionStateTracker.create(
                            this.getDatasource().getDruidDbType(),
                            this.conn
                    );
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            return this.conn;
        }
    }

    public QueryTransactionState getTransactionState() {
        QueryTransactionStateTracker tracker = this.transactionStateTracker;
        return tracker == null ? QueryTransactionState.UNKNOWN : tracker.currentState();
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
        } finally {
            this.executionLock.unlock();
        }
    }

    private void handleSerialPacket(Packet packet) {

        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case "close_data_view" -> {
                var name = (String) packet.getData();
                this.dataViews.remove(name);
                log.info("close data view {}", name);
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


            case QueryConsoleAction.ACTION_CANCEL -> {
                this.onCancel();
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
        var data = (Map<String, Object>) action.getData();
        var chunk = (String) data.get("chunk");
        var index = (Integer) data.get("index");
        var total = (Integer) data.get("total");

        if (chunk == null || index == null || total == null || total <= 0) {
            this.getConsoleLogger().error("invalid sql chunk");
            this.resetSQLChunks();
            return;
        }
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
        var dataView = this.dataViews.get(action.getDataView());
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
                var result = this.tableChangesPreviewService.preview(context, action.getDataView(), request);
                this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, result);
            } catch (IllegalArgumentException e) {
                this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, this.rejectedPreview(dataView, e.getMessage()));
            }
            return;
        }
        if (DataViewAction.ACTION_SAVE_CHANGES.equals(action.getAction())) {
            var request = GSON.fromJson(GSON.toJson(action.getData()), SaveChangesRequest.class);
            SaveChangesResult result = this.saveQueryChanges(dataView, action.getDataView(), request);
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_RESULT, result);
            return;
        }
        try {
            dataView.getStateManager().getState().setLoading(true);
            dataView.getStateManager().commit();

            dataView.doAction(action);

            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(action.getDataView(), dataView.getData()));

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

        Connection connection;
        QueryTransactionStateTracker tracker;
        QueryTransactionProbeResult beforeSave;
        try {
            connection = this.getConnection();
            tracker = this.transactionStateTracker;
            if (tracker == null) {
                return this.rejectedSave(dataView, QUERY_TRANSACTION_PROBE_FAILED);
            }
            beforeSave = tracker.probeNow();
        } catch (RuntimeException e) {
            log.warn("probe QueryConsole transaction state before DataView save failed", e);
            return this.rejectedSave(dataView, QUERY_TRANSACTION_PROBE_FAILED);
        }

        if (beforeSave.probeFailed()) {
            return this.rejectedSave(dataView, QUERY_TRANSACTION_PROBE_FAILED);
        }

        SaveExecutionContext executionContext;
        switch (beforeSave.state()) {
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
            return this.tableChangesSaveService.save(
                    context,
                    actionDataView,
                    request,
                    executionContext,
                    SessionManager.getCurrentSession()
            );
        } catch (IllegalArgumentException e) {
            return this.rejectedSave(dataView, e.getMessage());
        } finally {
            QueryTransactionProbeResult afterSave = tracker.probeNow();
            if (afterSave.probeFailed()) {
                log.warn("probe QueryConsole transaction state after DataView save failed");
            }
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
        result.setDataView(dataView.getTitle());
        return result;
    }

    private SaveChangesResult rejectedSave(DataView dataView, String reason) {
        SaveChangesResult result = new SaveChangesResult();
        result.setSuccess(false);
        result.setAllowed(false);
        result.setReason(reason);
        result.setDataView(dataView.getTitle());
        return result;
    }

    public void onCancel() {
        try {
            var plan = this.currentPlan;
            if (plan != null && plan.getStatement() != null) {
                plan.cancel();
                this.getConsoleLogger().error("cancel query: %s", plan.getTargetSQL());
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
        this.stateManager.commit();
        var session = SessionManager.getCurrentSession();

        var aclResult = session.checkACL(sql, this.getConnection());
        if (aclResult != null) {
            if (aclResult.getRiskLevel() == Common.RiskLevel.Reject || aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject) {
                this.getConsoleLogger().error("%s", MessageUtils.get("ACLRejectError"));
                CommandRecord commandRecord = new CommandRecord(sql);
                commandRecord.setRiskLevel(aclResult.getRiskLevel());
                session.recordCommand(commandRecord);

                this.getState().setInQuery(false);
                this.stateManager.commit();
                return;
            }

            if (aclResult.isNotify()) {

                var dialog = new Dialog(MessageUtils.get("Warning"));
                dialog.setBody(MessageUtils.get("CommandWarningDialogMessage"));
                var countDownLatch = new CountDownLatch(1);
                AtomicBoolean hasNext = new AtomicBoolean(true);

                dialog.addButton(new Button(MessageUtils.get("Submit"), "submit", countDownLatch::countDown));

                dialog.addButton(new Button(MessageUtils.get("Cancel"), "cancel", () -> {
                    hasNext.set(false);
                    countDownLatch.countDown();
                    this.getConsoleLogger().warn(MessageUtils.get("ExecutionCanceled"));
                }));

                SessionManager.getCurrentSession().getController().showDialog(dialog);

                try {
                    countDownLatch.await();

                    if (!hasNext.get()) {
                        this.getState().setInQuery(false);
                        this.stateManager.commit();
                        return;
                    }

                } catch (InterruptedException e) {
                    this.getState().setInQuery(false);
                    this.stateManager.commit();

                    this.getConsoleLogger().error("获取结果失败!");
                } finally {
                    SessionManager.getCurrentSession().getController().closeDialog();
                }
            }
        }


        try {
            var stmts = this.getSqlActuator().parseSQL(SQL.of(sql));
            var clearOthers = true;
            for (String stmt : stmts) {
                var dataView = this.runSingleSQL(stmt, aclResult);
                if (!dataView.isHasTable()) {
                    this.getConsoleLogger().success("%s , %s: %d",
                            MessageUtils.get("ExecuteSuccess"),
                            MessageUtils.get("AffectedRows"), dataView.getUpdateCount());
                } else {
                    this.sendDataView(dataView, clearOthers);
                    clearOthers = false;
                }
            }
            this.ensureCurrentSchema();
        } catch (ParserException e) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("ParseError"), e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("ParseError"), e.getMessage()));
        } catch (SQLException e) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("ExecuteError"), e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("ExecuteError"), e.getMessage()));
        } finally {
            this.getState().setInQuery(false);
            this.getState().setCanCancel(false);
            this.stateManager.commit();
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
        DataView dataView = new DataView(sourceSQL, this.getPacketIO(), this.getConsoleLogger());
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
            plan.generateTargetSQL();
            this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());

            this.currentPlan = plan;

            this.getState().setCanCancel(true);
            this.stateManager.commit();

            try {
                var result = plan.executeWithAudit();
                this.getConsoleLogger().success(result);
                return result;
            } finally {
                this.currentPlan = null;
                this.getState().setCanCancel(false);
                this.stateManager.commit();
            }
        });


        dataView.loadData();

        return dataView;
    }


    private void sendDataView(DataView dataView, boolean clearOthers) {
        if (clearOthers) {
            var forDeleteDataViewTitles = new ArrayList<String>();
            for (var title : this.dataViews.keySet()) {
                if (!dataView.getTitle().equals(title) && !this.dataViews.get(title).getStateManager().getState().isPinned()) {
                    forDeleteDataViewTitles.add(title);
                }
            }
            forDeleteDataViewTitles.forEach(this.dataViews.keySet()::remove);
            this.getPacketIO().sendPacket("close_data_view", forDeleteDataViewTitles);
        }

        if (!this.dataViews.containsKey(dataView.getTitle())) {
            this.getPacketIO().sendPacket("new_data_view", Map.of("title", dataView.getTitle()));
        }

        this.dataViews.put(dataView.getTitle(), dataView);
        this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(dataView.getTitle(), dataView.getData()));
        dataView.getStateManager().commit();
    }


    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        var plan = this.currentPlan;
        if (plan != null) {
            try {
                // flush
                var session = SessionManager.getCurrentSession();
                var lastCmd = plan.getTargetSQL();
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
            this.closeConnection();
        } finally {
            this.executionLock.unlock();
        }
        log.info("console closed");
    }

    private void closeConnection() {
        synchronized (this.connectionMonitor) {
            if (!this.connectionClosed.compareAndSet(false, true) || this.conn == null) {
                return;
            }
            try {
                this.conn.close();
            } catch (SQLException | RuntimeException e) {
                log.warn("close query console connection failed", e);
            }
        }
    }
}
