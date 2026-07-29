package org.jumpserver.chen.framework.datasource.edit.bind;

import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

@Slf4j
public class PreparedStatementBinder {

    public void bind(PreparedStatement statement, PreparedTableChangeCommand command) throws SQLException {
        for (int i = 0; i < command.getParameters().size(); i++) {
            PreparedTableChangeCommand.Parameter parameter = command.getParameters().get(i);
            bindConvertedValue(
                    statement,
                    i + 1,
                    parameter
            );
        }
    }

    private void bindConvertedValue(
            PreparedStatement statement,
            int index,
            PreparedTableChangeCommand.Parameter parameter
    ) throws SQLException {
        try {
            if (parameter.isValueIsNull() || parameter.getValue() == null) {
                statement.setNull(index, parameter.getJdbcType() != null ? parameter.getJdbcType() : Types.NULL);
                return;
            }
            if (isJsonOther(parameter)) {
                statement.setObject(index, parameter.getValue(), Types.OTHER);
                return;
            }
            statement.setObject(index, parameter.getValue());
        } catch (SQLException e) {
            log.warn(
                    "bind save changes parameter failed, index={}, paramName={}, column={}, isNull={}, jdbcType={}, dbType={}, valueClass={}, sqlState={}, vendorCode={}, message={}",
                    index,
                    parameter.getName(),
                    parameter.getColumn(),
                    parameter.isValueIsNull(),
                    parameter.getJdbcType(),
                    parameter.getDbType(),
                    parameter.getValue() != null ? parameter.getValue().getClass().getName() : null,
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            throw e;
        }
    }

    private boolean isJsonOther(PreparedTableChangeCommand.Parameter parameter) {
        if (parameter.getDbType() != DbType.postgresql ||
                parameter.getJdbcType() == null ||
                parameter.getJdbcType() != Types.OTHER) {
            return false;
        }
        String type = TableEditTypeCodecs.normalizeType(parameter.getField());
        return "json".equals(type) || "jsonb".equals(type);
    }
}
