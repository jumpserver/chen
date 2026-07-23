package org.jumpserver.chen.framework.datasource.base;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.HexUtils;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.jumpserver.chen.framework.utils.ReflectUtils;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
public abstract class BaseSQLActuator implements SQLActuator {

    @Getter
    private final DbType druidDbType;
    private static final int MAX_TEXT_DISPLAY_LENGTH = 1024 * 1024;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
    private static final DateTimeFormatter LOCAL_DATE_TIME_DISPLAY_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final DateTimeFormatter LOCAL_TIME_DISPLAY_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final DateTimeFormatter OFFSET_DATE_TIME_DISPLAY_FORMATTER = new DateTimeFormatterBuilder()
            .append(LOCAL_DATE_TIME_DISPLAY_FORMATTER)
            .appendOffsetId()
            .toFormatter();
    private static final DateTimeFormatter OFFSET_TIME_DISPLAY_FORMATTER = new DateTimeFormatterBuilder()
            .append(LOCAL_TIME_DISPLAY_FORMATTER)
            .appendOffsetId()
            .toFormatter();
    private ConnectionManager connectionManager;
    private Connection connection;

    public DbType getDbType() {
        return druidDbType;
    }

    public BaseSQLActuator(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.druidDbType = connectionManager.getDatasource().getDruidDbType();
    }

    protected BaseSQLActuator(BaseSQLActuator sqlActuator, Connection connection) {
        this.druidDbType = sqlActuator.getDruidDbType();
        this.connection = connection;
    }


    @Override
    public int getAffectedRows(SQL sql) throws SQLException {
        var result = 0;

        var sqlStmts = SQLUtils.parseStatements(sql.getSql(), this.druidDbType);

        if (sqlStmts.size() != 1) {
            return -1;
        }

        var sqlStmt = sqlStmts.get(0);

        if (sqlStmt instanceof SQLUpdateStatement || sqlStmt instanceof SQLDeleteStatement || sqlStmt instanceof SQLInsertStatement) {
            var conn = this.getConnection();
            try {
                conn.setAutoCommit(false);
                var stmt = conn.createStatement();
                stmt.execute(sqlStmt.toString());

                result = stmt.getUpdateCount();

                conn.rollback();
                stmt.close();
            } finally {
                if (this.connection == null) {
                    conn.close();
                } else {
                    conn.setAutoCommit(true);
                }
            }
        }
        return result;
    }

    @Override
    public List<String> parseSQL(SQL sql) {
        return SQLUtils.parseStatements(sql.getSql(), this.druidDbType).stream()
                .map(stmt -> SQLUtils.toSQLString(stmt, this.druidDbType))
                .toList();
    }

    @Override
    public <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) throws SQLException {
        List<T> objects = new ArrayList<>();
        try (Connection conn = this.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                T object = clazz.getDeclaredConstructor().newInstance();
                for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                    if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > rs.getMetaData().getColumnCount()) {
                        continue;
                    }
                    ReflectUtils.setFieldValue(object, entry.getKey(), rs.getObject(entry.getValue()));
                }
                objects.add(object);
            }
        } catch (Exception e) {
            var msg = "run sql %s error, %s".formatted(sql, e.getMessage());
            throw new SQLException(msg);
        }
        return objects;
    }

    @Override
    public SQLQueryResult execute(SQL sql) throws SQLException {
        var plan = this.createPlan(sql);
        return plan.execute();
    }


    @Override
    public SQLQueryResult execute(SQLExecutePlan plan) throws SQLException {
        String sql = plan.getTargetSQL();
        SQLQueryResult result = new SQLQueryResult(sql);
        result.setAclResult(plan.getAclResult());
        try {
            Statement statement = plan.createStatement();
            this.executeStatement(plan, statement, result);
        } finally {
            if (plan.getConnection() instanceof DruidPooledConnection) {
                plan.getConnection().close();
            }
        }
        return result;
    }

    private void executeStatement(SQLExecutePlan plan, Statement statement, SQLQueryResult result) throws SQLException {
        try (statement) {
            result.setStartTime(new Time(System.currentTimeMillis()));

            var hasResult = statement.execute(plan.getTargetSQL());
            result.setHasResultSet(hasResult);

            result.setQueryFinishedTime(new Time(System.currentTimeMillis()));

            if (hasResult) {
                var resultSet = statement.getResultSet();
                var metaData = resultSet.getMetaData();
                var columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    Field field = new Field();

                    var fieldName = StringUtils.isNotEmpty(metaData.getColumnLabel(i)) ?
                            metaData.getColumnLabel(i) : metaData.getColumnName(i);
                    field.setName(fieldName);
                    result.getFields().add(field);
                }

                while (resultSet.next()) {
                    List<Object> fs = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        try {
                            fs.add(this.normalizeJdbcValue(resultSet.getObject(i)));
                        } catch (NoClassDefFoundError e) {
                            log.error(e.getMessage());
                        }
                    }
                    result.getData().add(fs);
                }
                resultSet.close();
                result.setFetchFinishedTime(new Time(System.currentTimeMillis()));

                var total = this.count(plan);
                if (total < 0) {
                    result.setTotal(result.getData().size());
                } else {
                    result.setPaged(true);
                    result.setTotal(total);
                }

            } else {
                result.setUpdateCount(statement.getUpdateCount());
            }
            result.setEndTime(new Time(System.currentTimeMillis()));
        } catch (Exception e) {
            throw new SQLException(e.getMessage());
        }
    }

    protected Object normalizeJdbcValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        var temporalValue = this.formatTemporalValue(value);
        if (temporalValue != null) {
            return temporalValue;
        }

        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }

        if (value instanceof Long || value instanceof BigInteger) {
            return value.toString();
        }

        if (value instanceof java.sql.Array jdbcArray) {
            return this.toDisplayArray(jdbcArray);
        }

        if (value instanceof Clob clob) {
            return this.readClob(clob);
        }

        if (value instanceof SQLXML sqlxml) {
            return this.readSqlXml(sqlxml);
        }

        if (value instanceof byte[] bytes) {
            return HexUtils.bytesToHex(bytes);
        }

        if (value instanceof Blob blob) {
            if (blob.getClass().getName().equals("oracle.sql.BLOB")) {
                return blob.toString();
            }
            return HexUtils.bytesToHex(blob.getBytes(1, (int) blob.length()));
        }

        if (value.getClass().getSimpleName().equalsIgnoreCase("pgobject")) {
            return value.toString();
        }

        if (value.getClass().getName().equals("microsoft.sql.DateTimeOffset")) {
            return value.toString();
        }

        return value;
    }

    private String formatTemporalValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return LOCAL_DATE_TIME_DISPLAY_FORMATTER.format(timestamp.toLocalDateTime());
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return LOCAL_TIME_DISPLAY_FORMATTER.format(time.toLocalTime());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return LOCAL_DATE_TIME_DISPLAY_FORMATTER.format(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof LocalTime localTime) {
            return LOCAL_TIME_DISPLAY_FORMATTER.format(localTime);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return OFFSET_DATE_TIME_DISPLAY_FORMATTER.format(offsetDateTime);
        }
        if (value instanceof OffsetTime offsetTime) {
            return OFFSET_TIME_DISPLAY_FORMATTER.format(offsetTime);
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            var formatted = OFFSET_DATE_TIME_DISPLAY_FORMATTER.format(zonedDateTime);
            if (!(zonedDateTime.getZone() instanceof ZoneOffset)) {
                formatted += "[" + zonedDateTime.getZone().getId() + "]";
            }
            return formatted;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return null;
    }

    private String toDisplayArray(java.sql.Array jdbcArray) throws SQLException {
        try {
            var text = jdbcArray.toString();
            if (StringUtils.isNotBlank(text) && !isDefaultObjectToString(jdbcArray, text)) {
                return text;
            }
            return formatJdbcArray(jdbcArray.getArray());
        } finally {
            try {
                jdbcArray.free();
            } catch (SQLException e) {
                log.debug("free jdbc array failed", e);
            }
        }
    }

    private String readClob(Clob clob) throws SQLException {
        try (Reader reader = clob.getCharacterStream()) {
            if (reader != null) {
                return this.readDisplayText(reader);
            }

            long length = Math.min(clob.length(), MAX_TEXT_DISPLAY_LENGTH + 1L);
            return this.truncateDisplayText(clob.getSubString(1, (int) length));
        } catch (IOException e) {
            throw new SQLException("read clob failed", e);
        } finally {
            try {
                clob.free();
            } catch (SQLException e) {
                log.debug("free clob failed", e);
            }
        }
    }

    private String readSqlXml(SQLXML sqlxml) throws SQLException {
        try (Reader reader = sqlxml.getCharacterStream()) {
            if (reader != null) {
                return this.readDisplayText(reader);
            }

            return this.truncateDisplayText(sqlxml.getString());
        } catch (IOException e) {
            throw new SQLException("read sqlxml failed", e);
        } finally {
            try {
                sqlxml.free();
            } catch (SQLException e) {
                log.debug("free sqlxml failed", e);
            }
        }
    }

    private String formatJdbcArray(Object arrayValue) {
        if (arrayValue == null) {
            return null;
        }
        if (!arrayValue.getClass().isArray()) {
            return arrayValue.toString();
        }

        int length = java.lang.reflect.Array.getLength(arrayValue);
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (int i = 0; i < length; i++) {
            var item = java.lang.reflect.Array.get(arrayValue, i);
            if (item instanceof byte[] bytes) {
                joiner.add(HexUtils.bytesToHex(bytes));
            } else if (item != null && item.getClass().isArray()) {
                joiner.add(formatJdbcArray(item));
            } else if (item == null) {
                joiner.add("NULL");
            } else {
                joiner.add(item.toString());
            }
        }
        return joiner.toString();
    }

    private String readDisplayText(Reader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];

        int read;
        while ((read = reader.read(buffer)) != -1) {
            int remaining = MAX_TEXT_DISPLAY_LENGTH - builder.length();
            if (read > remaining) {
                builder.append(buffer, 0, remaining);
                builder.append(TRUNCATED_SUFFIX);
                return builder.toString();
            }

            builder.append(buffer, 0, read);
            if (builder.length() == MAX_TEXT_DISPLAY_LENGTH && reader.read() != -1) {
                builder.append(TRUNCATED_SUFFIX);
                return builder.toString();
            }
        }

        return builder.toString();
    }

    private String truncateDisplayText(String text) {
        if (text == null || text.length() <= MAX_TEXT_DISPLAY_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_DISPLAY_LENGTH) + TRUNCATED_SUFFIX;
    }

    private boolean isDefaultObjectToString(Object value, String text) {
        return text.equals(value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)));
    }

    @Override
    public SQLQueryResult executeWithAudit(SQL sql) throws SQLException {
        var plan = this.createPlan(sql);
        return plan.executeWithAudit();
    }

    @Override
    public SQLQueryResult executeWithAudit(SQLExecutePlan plan) throws SQLException {
        var sess = SessionManager.getCurrentSession();
        try {
            return sess.withAudit(plan.getTargetSQL(), () -> this.execute(plan));
        } catch (CommandRejectException e) {
            throw new SQLException(e.getMessage());
        }
    }

    public int count(SQL sql) throws SQLException {
        return this.count(this.createPlan(sql));
    }

    public int count(SQLExecutePlan plan) throws SQLException {
        if (plan.getTargetSQLStatement() instanceof SQLSelectStatement) {
            var limit = PageUtils.getLimit(plan.getSourceSQL(), plan.getDruidDbType());
            if (limit > 0) {
                return -1;
            }
            var countSQL = PageUtils.count(plan.getSourceSQL(), plan.getDruidDbType());
            try (Statement stmt = plan.createStatement()) {
                var resultSet = stmt.executeQuery(countSQL);
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return -1;
    }


    @Override
    public SQLActuator withConnection(Connection connection) {
        try {
            return this.getClass()
                    .getDeclaredConstructor(this.getClass(),
                            Connection.class)
                    .newInstance(this, connection);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql) throws SQLException {
        SQLExecutePlan plan = new SQLExecutePlan(sql.getSql(), this.getDruidDbType());
        this.createPlan(plan);
        return plan;
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams queryParams) throws SQLException {
        SQLExecutePlan plan = new SQLExecutePlan(sql.getSql(), this.getDruidDbType());
        plan.setSqlQueryParams(queryParams);
        this.createPlan(plan);
        plan.generateTargetSQL();
        return plan;
    }

    private void createPlan(SQLExecutePlan plan) throws SQLException {
        plan.setSqlActuator(this);
        plan.setConnection(this.getConnection());
    }

    private Connection getConnection() throws SQLException {
        return this.connection != null ? this.connection : this.connectionManager.getConnection();
    }
}
