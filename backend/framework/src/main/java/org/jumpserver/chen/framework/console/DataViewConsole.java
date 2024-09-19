package org.jumpserver.chen.framework.console;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.State;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataViewConsole extends AbstractConsole {

    private DataView tableDataView;
    private StateManager<State> stateManager;

    private String schema;
    private String table;

    public DataViewConsole(Datasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
    }


    @Override
    public void onInit(Connect connect) {
        this.schema = TreeUtils.getValue(connect.getNodeKey(), "schema");
        this.table = StringUtils.isEmpty(TreeUtils.getValue(connect.getNodeKey(), "table")) ?
                TreeUtils.getValue(connect.getNodeKey(), "view") : TreeUtils.getValue(connect.getNodeKey(), "table");

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
        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case Packet.TYPE_DATA_VIEW_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), DataViewAction.class);
                this.onDataViewAction(action);
            }
        }
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
        this.getPacketIO().sendPacket("new_data_view", Map.of("title", viewTitle));
        var dataView = new DataView(viewTitle, this.getPacketIO(), this.getConsoleLogger());

        var session = SessionManager.getCurrentSession();
        dataView.setLoadDataInterface((sqlQueryParams) -> {
            var plan = this.getDatasource()
                    .getConnectionManager()
                    .getSqlActuator()
                    .createPlan(schemaName, tableName, null);
            var sql = plan.getTargetSQL();

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

                SessionManager.getCurrentSession().getController().showDialog(dialog);

                try {
                    countDownLatch.await();

                    if (!hasNext.get()) {
                        throw new SQLException(MessageUtils.get("ExecutionCanceled"));
                    }

                } catch (InterruptedException e) {
                    this.stateManager.commit();

                    this.getConsoleLogger().error("get result error");
                } finally {
                    SessionManager.getCurrentSession().getController().closeDialog();
                }
            }



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
    }
}
