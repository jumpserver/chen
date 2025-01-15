package org.jumpserver.chen.framework.console;

import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.QueryConsoleState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class QueryConsole extends AbstractConsole {

    private final Datasource datasource;
    private Connection conn;
    private volatile SQLExecutePlan currentPlan;
    private StateManager<QueryConsoleState> stateManager;
    private final Map<String, DataView> dataViews = new HashMap<>();

    public QueryConsole(Datasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
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
        super.onInit(connect);
        this.onConnect(connect);
    }

    public void onConnect(Connect connect) {
        this.getConsoleLogger().info("Websocket" + MessageUtils.get("Connected"));

        this.stateManager = new StateManager<>(new QueryConsoleState(this.getTitle())
                , this.getPacketIO());
        this.getState().setLoading(true);
        this.stateManager.commit();

        var context = TreeUtils.getValue(connect.getNodeKey(), this.getDatasource().getConnectionManager().getContextKey());
        try {
            var currentContext = this.getSqlActuator().getCurrentSchema();


            if (StringUtils.isEmpty(context)) {
                context = currentContext;
            }

            if (currentContext != null && !currentContext.equals(context)) {
                this.getSqlActuator().changeSchema(context);
            }
            var schemas = this.getSqlActuator().getSchemas();
            this.getState().setContexts(schemas);
            this.getState().setCurrentContext(context);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("ConnectError") + ": %s", e.getMessage());
        }

        this.getState().setLoading(false);
        this.stateManager.commit();

    }

    private Connection getConnection() {
        if (this.conn == null) {
            try {
                this.conn = this.getDatasource().getConnectionManager().getPhysicalConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return this.conn;
    }


    @Override
    public void handle(Packet packet) {

        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case "close_data_view" -> {
                var name = (String) packet.getData();
                this.dataViews.remove(name);
                log.info("close data view {}", name);
            }

            case Packet.TYPE_QUERY_CONSOLE_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), QueryConsoleAction.class);
                this.onAction(action);

            }
            case Packet.TYPE_DATA_VIEW_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), DataViewAction.class);
                this.onDataViewAction(action);
            }
            default -> log.warn("Unknown packet type {}", packet.getType());
        }
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

    private final ConcurrentHashMap<Integer, String> sqlChunks = new ConcurrentHashMap<>();
    private CountDownLatch latch;
    private int expectedChunks = -1;

    private void handleSQLChunk(QueryConsoleAction action) {
        var data = (Map<String, Object>) action.getData();
        var chunk = (String) data.get("chunk");
        var index = (Integer) data.get("index");
        var total = (Integer) data.get("total");

        synchronized (this) {
            if (expectedChunks == -1) {
                expectedChunks = total;
                latch = new CountDownLatch(total);
            }
        }

        if (sqlChunks.putIfAbsent(index, chunk) == null) {
            latch.countDown();
        }
    }

    /**
     * 处理分段 SQL 接收完成
     */
    private void handleSQLComplete() {
        try {
            // 等待所有分段接收完成
            latch.await();

            // 按照索引顺序合并所有分段
            StringBuilder sqlBuilder = new StringBuilder();
            for (int i = 0; i < expectedChunks; i++) {
                sqlBuilder.append(sqlChunks.get(i));
            }

            // 合并完成后清理缓存
            var sql = sqlBuilder.toString();
            sqlChunks.clear();
            expectedChunks = -1;

            // 执行完整 SQL
            this.getState().setInQuery(true);
            this.stateManager.commit();

            this.onSQL(sql);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            this.getState().setInQuery(false);
            this.stateManager.commit();
        }
    }

    private void onDataViewAction(DataViewAction action) {
        var dataView = this.dataViews.get(action.getDataView());
        if (dataView == null) {
            log.error("data view {} not found", action.getDataView());
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

    public void onCancel() {
        try {
            if (this.currentPlan != null) {
                this.currentPlan.cancel();
                this.getConsoleLogger().error("cancel query: %s", this.currentPlan.getTargetSQL());
            }
        } catch (SQLException e) {
            log.error("cancel failed ", e);
        }
    }

    public void onManualChangeContext(String context) {
        if (StringUtils.equals(this.getState().getCurrentContext(), context)) {
            return;
        }
        try {
            this.getState().setEditorLoading(true);
            this.stateManager.commit();

            this.getSqlActuator().changeSchema(context);
            this.getState().setCurrentContext(context);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("ChangeContextError") + ": %s", e.getMessage());
        } finally {
            this.getState().setEditorLoading(false);
            this.stateManager.commit();
        }

    }


    public void onSQLFile(String filename) {
        var filePath = SessionManager.getCurrentSession().getTempPath().resolve(filename);
        var file = filePath.toFile();

        if (!file.exists()) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_found"), filename);
            return;
        }
        if (!file.isFile()) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_file"), filename);
            return;
        }
        if (!file.canRead()) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_not_readable"), filename);
            return;
        }

        try {
            var sql = Files.readString(file.toPath());
            this.onSQL(sql);
        } catch (IOException e) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_read_error"), e.getMessage());
        } finally {
            file.delete();
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

        SQLExecutePlan plan = this.datasource
                .getConnectionManager()
                .getSqlActuator()
                .withConnection(this.getConnection())
                .createPlan(SQL.of(sql));

        plan.setAclResult(aclResult);
        DataView dataView = new DataView(plan.getSourceSQL(), this.getPacketIO(), this.getConsoleLogger());
        dataView.setSql(plan.getSourceSQL());

        dataView.setLoadDataInterface((sqlQueryParams) -> {
            sqlQueryParams.setTimeout(this.getState().getTimeout());

            plan.setSqlQueryParams(sqlQueryParams);
            plan.generateTargetSQL();

            this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());

            this.currentPlan = plan;

            this.getState().setCanCancel(true);
            this.stateManager.commit();

            var result = plan.executeWithAudit();
            this.currentPlan = null;

            this.getConsoleLogger().success(result);
            return result;
        });


        dataView.loadData();

        this.getState().setCanCancel(false);
        this.stateManager.commit();

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
        log.info("console closed");
    }
}
