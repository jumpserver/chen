package org.jumpserver.chen.framework.datasource.sql;

import com.github.freva.asciitable.AsciiTable;
import lombok.Data;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.jms.acl.ACLResult;

import java.sql.Time;
import java.util.ArrayList;
import java.util.List;


@Data
public class SQLQueryResult {
    private String sql;
    private int total = -1;
    private boolean paged;
    private int updateCount;
    private boolean hasResultSet = true;
    private List<Field> fields = new ArrayList<>();
    private List<List<Object>> data = new ArrayList<>();

    private Time startTime;
    private Time endTime;
    private Time queryFinishedTime;
    private Time fetchFinishedTime;

    private ACLResult aclResult;


    public long getTotalTimeUsed() {
        if (this.hasResultSet) {
            return this.fetchFinishedTime.getTime() - this.startTime.getTime();
        }
        return this.endTime.getTime() - this.startTime.getTime();
    }

    public long getQueryTimeUsed() {
        return this.queryFinishedTime.getTime() - this.startTime.getTime();
    }

    public long getFetchTimeUsed() {
        return this.fetchFinishedTime.getTime() - this.queryFinishedTime.getTime();
    }

    public SQLQueryResult(String sql) {
        this.sql = sql;
    }

    public String getOutput() {
        if (!this.hasResultSet) {
            return String.format("Query OK, %d rows affected", this.updateCount);
        }
        String[] headers = fields.stream().map(Field::getName).toArray(String[]::new);
        Object[][] data = this.data.stream().map(List::toArray).toArray(Object[][]::new);
        return AsciiTable.getTable(headers, data);
    }

}
