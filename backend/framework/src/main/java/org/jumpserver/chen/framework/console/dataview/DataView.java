package org.jumpserver.chen.framework.console.dataview;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.entity.response.SQLResult;
import org.jumpserver.chen.framework.console.state.DataViewState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
public class DataView extends SQLResult {
    private final String title;
    private final StateManager<DataViewState> stateManager;
    private LoadDataInterface loadDataInterface;

    private DataViewData data = new DataViewData();
    private int updateCount;
    private boolean hasTable = true;
    private DataViewState state;

    private Logger consoleLogger;

    public DataView(String title, PacketIO packetIO, Logger logger) {
        this.title = title;
        this.state = new DataViewState(title);
        this.stateManager = new StateManager<>(this.state, packetIO);
        this.consoleLogger = logger;
    }


    public void doAction(DataViewAction action) throws SQLException {
        switch (action.getAction()) {
            case DataViewAction.ACTION_FIRST_PAGE -> {
                this.firstPage();
            }
            case DataViewAction.ACTION_PREV_PAGE -> {
                this.prevPage();
            }
            case DataViewAction.ACTION_NEXT_PAGE -> {
                this.nextPage();
            }
            case DataViewAction.ACTION_LAST_PAGE -> {
                this.lastPage();
            }
            case DataViewAction.ACTION_REFRESH -> {
                this.refresh();
            }
            case DataViewAction.ACTION_TOGGLE_PINNED -> {
                this.getStateManager().getState().setPinned(!this.getStateManager().getState().isPinned());
            }
            case DataViewAction.ACTION_CHANGE_LIMIT -> {
                this.changeLimit((int) action.getData());
            }
            case DataViewAction.ACTION_EXPORT -> {
                this.export((String) action.getData());
            }
        }
    }

    public void loadData() throws SQLException {
        SQLQueryParams queryParams = new SQLQueryParams();
        queryParams.setLimit(this.state.getLimit());
        queryParams.setOffset((this.state.getPage() - 1) * this.state.getLimit());

        var result = this.loadDataInterface
                .loadData(queryParams);

        this.fullData(result);
    }

    private void fullData(SQLQueryResult result) {
        if (!result.isHasResultSet()) {
            this.hasTable = false;
            this.updateCount = result.getUpdateCount();
            return;
        }

        this.state.setPaged(result.isPaged());

        this.data.getFields().clear();
        this.data.getData().clear();

        this.getStateManager().getState().setTotal(result.getTotal());


        this.data.setFields(result.getFields());

        Map<String, Integer> fieldNumMap = new HashMap<>();

        this.data.getFields().forEach(field -> {
            if (fieldNumMap.containsKey(field.getName())) {
                var fieldName = field.getName();
                var num = fieldNumMap.get(field.getName());
                field.setName(field.getName() + "(" + num + ")");
                fieldNumMap.put(field.getName(), fieldNumMap.get(fieldName) + 1);
            } else {
                fieldNumMap.put(field.getName(), 1);
            }
        });


        for (List<Object> row : result.getData()) {
            Map<String, Object> map = new HashMap<>();
            for (int i = 0; i < row.size(); i++) {
                map.put(this.data.getFields().get(i).getName(), row.get(i));
            }
            this.data.getData().add(map);
        }
    }

    private static void writeString(BufferedWriter writer, Object object) throws IOException {
        var str = object.toString();

        if (str.contains(",")) {
            str = "\"" + str + "\"";
        }
        writer.write(str);
    }

    public void export(String scope) throws SQLException {
        var session = SessionManager.getCurrentSession();


        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        var f = session.createFile(String.format("data_%s.csv", timestamp));

        CommandRecord command = new CommandRecord(String.format("Export data: %s", this.title));

        try {
            if (!SessionManager.getCurrentSession().canDownload()) {
                session.getController().sendFile(f.getName());
                return;
            }
            var writer = Files.newBufferedWriter(f.toPath());

            if (scope.equals("current")) {
                for (Field field : this.data.getFields()) {
                    writeString(writer, field.getName());
                    writer.write(",");
                }
                writer.newLine();

                for (Map<String, Object> row : this.data.getData()) {
                    for (Field field : this.data.getFields()) {
                        if (row.get(field.getName()) == null) {
                            writer.write("NULL");
                            writer.write(",");
                        } else {
                            var obj = row.get(field.getName());
                            if (obj instanceof Date) {
                                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                writeString(writer, fmt.format(obj));
                            } else {
                                writeString(writer, obj);
                            }
                            writer.write(",");
                        }
                    }
                    writer.newLine();
                }
                command.setOutput(String.format("%d rows exported", this.data.getData().size()));
            }

            if (scope.equals("all")) {
                SQLQueryParams queryParams = new SQLQueryParams();
                queryParams.setLimit(-1);
                var result = this.loadDataInterface.loadData(queryParams);

                for (Field field : result.getFields()) {
                    writer.write(field.getName());
                    writer.write(",");
                }
                writer.newLine();

                for (List<Object> row : result.getData()) {
                    for (Object o : row) {
                        if (o == null) {
                            writer.write("NULL");
                            writer.write(",");
                        } else {
                            writer.write(o.toString());
                            writer.write(",");
                        }
                    }
                    writer.newLine();
                }
                command.setOutput(String.format("%d rows exported", result.getData().size()));
            }
            writer.flush();
            writer.close();

            this.consoleLogger.success(command.getOutput());
            session.recordCommand(command);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        session.getController().sendFile(f.getName());
    }


    public void firstPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void prevPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(this.getStateManager().getState().getPage() - 1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void nextPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(this.getStateManager().getState().getPage() + 1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }


    public void lastPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            if (this.state.getTotal() > 0) {
                var page = this.getState().getTotal() % this.getState().getLimit() > 0 ?
                        this.getState().getTotal() / this.getState().getLimit() + 1 : this.getState().getTotal() / this.getState().getLimit();
                this.getStateManager().getState().setPage(page);
            }
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void changeLimit(int limit) throws SQLException {
        var oldLimit = this.getStateManager().getState().getLimit();
        var oldPage = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(1);
            this.getStateManager().getState().setLimit(limit);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setLimit(oldLimit);
            this.getStateManager().getState().setPage(oldPage);
            throw e;
        }
    }

    public void refresh() throws SQLException {
        this.loadData();
    }


    public void sortBy(String field) {
    }
}
