package org.jumpserver.chen.framework.console;

import com.google.gson.Gson;
import lombok.Getter;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.context.ConsoleContext;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.State;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.edit.TableBrowseSaveExecutionContext;
import org.jumpserver.chen.framework.datasource.edit.TableChangesPreviewService;
import org.jumpserver.chen.framework.datasource.edit.TableChangesSaveService;
import org.jumpserver.chen.framework.datasource.edit.TableEditContext;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.DialogHandle;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class DataViewConsole extends AbstractConsole {
    private static final String PACKET_SAVE_CHANGES_PREVIEW_RESULT = "save_changes_preview_result";
    private static final String PACKET_SAVE_CHANGES_RESULT = "save_changes_result";
    private static final long WARNING_DIALOG_TIMEOUT_SECONDS = 300;
    private final TableChangesPreviewService tableChangesPreviewService = new TableChangesPreviewService();
    private final TableChangesSaveService tableChangesSaveService = new TableChangesSaveService();

    private DataView tableDataView;
    private StateManager<State> stateManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantLock executionLock = new ReentrantLock();

    @Getter
    private final String schema;
    @Getter
    private final String table;

    private static final Gson GSON = new Gson();

    public DataViewConsole(Datasource datasource, WebSocketSession ws, ConsoleContext context) {
        super(datasource, ws, context);
        this.schema = context.schema();
        this.table = context.table();
    }


    @Override
    public void onInit(Connect connect) {
        if (!this.beginExecution()) {
            return;
        }
        try {
            this.initialize(connect);
        } finally {
            this.executionLock.unlock();
        }
    }

    private void initialize(Connect connect) {
        var title = "";
        try {
            title = this.generateConsoleName();
        } catch (RuntimeException e) {
            this.getPacketIO().sendPacket("active_console", title);
            this.getPacketIO().sendPacket("close", null);
            return;
        }
        this.setTitle(title);
        this.stateManager = new StateManager<>(new State(title), this.getPacketIO());
        super.onInit(connect);
        this.onConnect(connect);
    }


    private String generateConsoleName() {
        var name = String.format("DataView: %s.%s", this.schema, this.table);
        if (SessionManager.getCurrentSession().getConsoles().get(name) != null) {
            throw new RuntimeException("console already exists");
        }

        return name;
    }

    @Override
    public void handle(Packet packet) {
        if ("ping".equals(packet.getType())) {
            this.getPacketIO().sendPacket("pong", null);
            return;
        }
        if (!this.beginExecution()) {
            return;
        }
        try {
            switch (packet.getType()) {
                case Packet.TYPE_DATA_VIEW_ACTION -> {
                    var action = GSON.fromJson(GSON.toJson(packet.getData()), DataViewAction.class);
                    this.onDataViewAction(action);
                }
            }
        } finally {
            this.executionLock.unlock();
        }
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

    public void onConnect(Connect connect) {
        this.getConsoleLogger().info("Websocket" + MessageUtils.get("Connected"));
        this.getConsoleLogger().info("view table: %s", this.getTitle());

        this.createDataView(this.schema, this.table);

        try {
            this.tableDataView.getStateManager().getState().setLoading(true);
            this.tableDataView.getStateManager().commit();

            this.tableDataView.loadData();
        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("FetchError"), e.getMessage()));
        } finally {
            this.tableDataView.getStateManager().getState().setLoading(false);
            this.tableDataView.getStateManager().commit();
        }
        this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(this.tableDataView.getTitle(), this.tableDataView.getData()));
        this.tableDataView.getStateManager().commit();

    }

    public void createDataView(String schemaName, String tableName) {

        var viewTitle = this.getTitle() + "child";
        var data = Map.of("title", viewTitle, "schema", this.schema, "table", this.table);
        this.getPacketIO().sendPacket("new_data_view", data);
        var dataView = new DataView(viewTitle, this.getPacketIO(), this.getConsoleLogger());

        var session = SessionManager.getCurrentSession();
        dataView.setLoadDataInterface((sqlQueryParams) -> {
            var basePlan = this.getDatasource()
                    .getConnectionManager()
                    .getSqlActuator()
                    .createPlan(schemaName, tableName, null);
            final String sql;
            try {
                sql = PageUtils.filter(
                        basePlan.getTargetSQL(),
                        this.getDatasource().getDruidDbType(),
                        sqlQueryParams.getFilter()
                );
            } catch (RuntimeException e) {
                throw new SQLException("Invalid data view WHERE condition: " + e.getMessage());
            } finally {
                basePlan.close();
            }

            var aclResult = session.checkACL(sql);
            if (aclResult != null && (aclResult.getRiskLevel() == Common.RiskLevel.Reject || aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject)) {
                this.getConsoleLogger().error("%s", MessageUtils.get("ACLRejectError"));
                CommandRecord commandRecord = new CommandRecord(sql);
                commandRecord.setRiskLevel(aclResult.getRiskLevel());
                session.recordCommand(commandRecord);

                this.stateManager.getState().setLoading(false);
                this.stateManager.commit();
                throw new SQLException(MessageUtils.get("ACLRejectError"));
            }

            if (aclResult!=null && aclResult.isNotify()) {

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

                var controller = SessionManager.getCurrentSession().getController();
                DialogHandle dialogHandle = controller.showDialog(dialog, () -> {
                    hasNext.set(false);
                    countDownLatch.countDown();
                });

                try {
                    if (!countDownLatch.await(WARNING_DIALOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        hasNext.set(false);
                        dialogHandle.cancel();
                    }

                    if (!hasNext.get()) {
                        throw new SQLException(MessageUtils.get("ExecutionCanceled"));
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    hasNext.set(false);
                    this.stateManager.commit();

                    this.getConsoleLogger().error("get result error");
                    throw new SQLException(MessageUtils.get("ExecutionCanceled"), e);
                } finally {
                    dialogHandle.close();
                }
            }


            var plan = this.getDatasource()
                    .getConnectionManager()
                    .getSqlActuator()
                    .createPlan(SQL.of(sql));
            plan.setSqlQueryParams(sqlQueryParams);
            plan.generateTargetSQL();

            plan.setAclResult(aclResult);
            this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());
            var result = plan.executeWithAudit();

            this.getConsoleLogger().success(result);
            return result;
        });

        this.tableDataView = dataView;
    }


    public void onDataViewAction(DataViewAction action) {
        if (DataViewAction.ACTION_SAVE_CHANGES_PREVIEW.equals(action.getAction())) {
            var request = GSON.fromJson(GSON.toJson(action.getData()), SaveChangesRequest.class);
            var context = new TableEditContext(
                    this.tableDataView.getTitle(),
                    this.schema,
                    this.table,
                    this.tableDataView.getData().getFields(),
                    this.getDatasource().getDruidDbType(),
                    true
            );
            context.setRowRefPrimaryKeys(this.tableDataView.getRowRefPrimaryKeys());
            var result = this.tableChangesPreviewService.preview(context, action.getDataView(), request);
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_PREVIEW_RESULT, result);
            return;
        }
        if (DataViewAction.ACTION_SAVE_CHANGES.equals(action.getAction())) {
            var request = GSON.fromJson(GSON.toJson(action.getData()), SaveChangesRequest.class);
            var context = new TableEditContext(
                    this.tableDataView.getTitle(),
                    this.schema,
                    this.table,
                    this.tableDataView.getData().getFields(),
                    this.getDatasource().getDruidDbType(),
                    true
            );
            context.setRowRefPrimaryKeys(this.tableDataView.getRowRefPrimaryKeys());
            var result = this.tableChangesSaveService.save(
                    context,
                    action.getDataView(),
                    request,
                    new TableBrowseSaveExecutionContext(this.getDatasource().getConnectionManager()),
                    SessionManager.getCurrentSession()
            );
            this.getPacketIO().sendPacket(PACKET_SAVE_CHANGES_RESULT, result);
            return;
        }

        try {
            this.tableDataView.getStateManager().getState().setLoading(true);
            this.tableDataView.getStateManager().commit();

            this.tableDataView.doAction(action);

            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(this.tableDataView.getTitle(), this.tableDataView.getData()));
            this.tableDataView.getStateManager().commit();
        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("FetchError"), e.getMessage()));
        } finally {
            this.tableDataView.getStateManager().getState().setLoading(false);
            this.tableDataView.getStateManager().commit();
        }
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
        this.executionLock.lock();
        try {
            // Wait for admitted work to observe cancellation and leave the execution section.
        } finally {
            this.executionLock.unlock();
        }
    }
}
