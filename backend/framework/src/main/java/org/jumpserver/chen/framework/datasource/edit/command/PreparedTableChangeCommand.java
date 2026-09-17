package org.jumpserver.chen.framework.datasource.edit.command;

import com.alibaba.druid.DbType;
import lombok.Data;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.ArrayList;
import java.util.List;

@Data
public class PreparedTableChangeCommand {
    private Operation operation;
    private String preparedSql;
    private String sourceColumn;
    private String pkColumn;
    private SaveChangesRequest.ChangeItem change;
    private SaveChangesRequest.InsertRow insertRow;
    private SaveChangesRequest.DeleteRow deleteRow;
    private List<Parameter> parameters = new ArrayList<>();

    public enum Operation {
        UPDATE,
        DELETE,
        INSERT
    }

    @Data
    public static class Parameter {
        private final String name;
        private final String column;
        private final Field field;
        private final Object value;
        private final boolean valueIsNull;
        private final Integer jdbcType;
        private final DbType dbType;

        public Parameter(String name, String column, Field field, Object value, boolean valueIsNull) {
            this(name, column, field, value, valueIsNull, field != null ? field.getJdbcType() : null, null);
        }

        public Parameter(String name, String column, Field field, Object value, boolean valueIsNull, DbType dbType) {
            this(name, column, field, value, valueIsNull, field != null ? field.getJdbcType() : null, dbType);
        }

        public Parameter(String name, String column, Field field, Object value, boolean valueIsNull, Integer jdbcType) {
            this(name, column, field, value, valueIsNull, jdbcType, null);
        }

        public Parameter(String name, String column, Field field, Object value, boolean valueIsNull, Integer jdbcType, DbType dbType) {
            this.name = name;
            this.column = column;
            this.field = field;
            this.value = value;
            this.valueIsNull = valueIsNull;
            this.jdbcType = jdbcType;
            this.dbType = dbType;
        }
    }
}
