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
import org.jumpserver.chen.framework.datasource.edit.analyzer.EditabilityReason;
import org.jumpserver.chen.framework.datasource.edit.pk.JdbcPrimaryKeyResolver;
import org.jumpserver.chen.framework.datasource.edit.analyzer.QueryResultEditabilityAnalyzer;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.HexUtils;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.jumpserver.chen.framework.utils.ReflectUtils;
import org.jumpserver.wisp.Common;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public abstract class BaseSQLActuator implements SQLActuator {

    @Getter
    private final DbType druidDbType;
    // Keep large JDBC text values bounded so one cell cannot fail or stall the whole result view.
    private static final int MAX_TEXT_DISPLAY_LENGTH = 1024 * 1024;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";
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

        for (var sqlStmt : sqlStmts) {
            if (sqlStmt instanceof SQLUpdateStatement || sqlStmt instanceof SQLDeleteStatement || sqlStmt instanceof SQLInsertStatement) {
                var conn = this.getConnection();
                try {
                    conn.setAutoCommit(false);
                    var stmt = conn.createStatement();
                    stmt.execute(sqlStmt.toString());

                    result += stmt.getUpdateCount();

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
        return this.execute(plan, true);
    }

    @Override
    public SQLQueryResult executeRaw(SQLExecutePlan plan) throws SQLException {
        return this.execute(plan, false);
    }

    private SQLQueryResult execute(SQLExecutePlan plan, boolean enrichResult) throws SQLException {
        String sql = plan.getTargetSQL();
        SQLQueryResult result = new SQLQueryResult(sql);
        result.setAclResult(plan.getAclResult());
        try {
            Statement statement = plan.createStatement();
            this.executeStatement(plan, statement, result, enrichResult);
        } finally {
            if (plan.getConnection() instanceof DruidPooledConnection) {
                plan.getConnection().close();
            }
        }
        return result;
    }

    private void executeStatement(
            SQLExecutePlan plan,
            Statement statement,
            SQLQueryResult result,
            boolean enrichResult
    ) throws SQLException {
        try (statement) {
            result.setStartTime(new Time(System.currentTimeMillis()));

            var hasResult = statement.execute(plan.getTargetSQL());
            result.setHasResultSet(hasResult);

            result.setQueryFinishedTime(new Time(System.currentTimeMillis()));

            if (hasResult) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    this.readResultSet(resultSet, result);
                }
                result.setFetchFinishedTime(new Time(System.currentTimeMillis()));
                if (enrichResult) {
                    markGeneratedColumns(plan.getConnection(), this.getDruidDbType(), result.getFields());
                    this.analyzeResultEditability(plan, result);
                }
                this.handleDataMasking(result);
                if (enrichResult) {
                    var total = this.count(plan);
                    if (total < 0) {
                        result.setTotal(result.getData().size());
                    } else {
                        result.setPaged(true);
                        result.setTotal(total);
                    }
                } else {
                    result.setTotal(result.getData().size());
                }
            } else {
                result.setUpdateCount(statement.getUpdateCount());
            }
            result.setEndTime(new Time(System.currentTimeMillis()));
        } catch (Exception e) {
            throw new SQLException(e.getMessage());
        }
    }

    private void readResultSet(ResultSet resultSet, SQLQueryResult result) throws SQLException {
        var metaData = resultSet.getMetaData();
        var columnCount = metaData.getColumnCount();
        for (int index = 1; index <= columnCount; index++) {
            result.getFields().add(buildField(metaData, index));
        }
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int index = 1; index <= columnCount; index++) {
                try {
                    row.add(this.normalizeJdbcValue(resultSet.getObject(index)));
                } catch (NoClassDefFoundError e) {
                    log.error(e.getMessage());
                }
            }
            result.getData().add(row);
        }
    }

    private void analyzeResultEditability(SQLExecutePlan plan, SQLQueryResult result) {
        var analyzer = new QueryResultEditabilityAnalyzer(
                this.getDruidDbType(),
                new JdbcPrimaryKeyResolver(plan.getConnection(), this.getDruidDbType())
        );
        analyzer.analyze(plan.getSourceSQL(), result.getFields());
    }

    static Field buildField(ResultSetMetaData metaData, int columnIndex) throws SQLException {
        Field field = new Field();

        String columnLabel = metaData.getColumnLabel(columnIndex);
        String columnName = metaData.getColumnName(columnIndex);
        String fieldName = StringUtils.isNotEmpty(columnLabel) ? columnLabel : columnName;
        field.setName(fieldName);
        field.setColumnName(columnName);
        field.setLabel(columnLabel);
        fillOptionalFieldMetadata(field, metaData, columnIndex);
        return field;
    }

    private static void fillOptionalFieldMetadata(Field field, ResultSetMetaData metaData, int columnIndex) {
        //后续还要靠 SQL AST 和主键解析再判断
        field.setSchema(getNullableMetadataValue(() -> metaData.getSchemaName(columnIndex), "schema", columnIndex));
        field.setTable(getNullableMetadataValue(() -> metaData.getTableName(columnIndex), "table", columnIndex));
        field.setType(getNullableMetadataValue(() -> metaData.getColumnTypeName(columnIndex), "type", columnIndex));
        try {
            field.setJdbcType(metaData.getColumnType(columnIndex));
        } catch (SQLException e) {
            log.debug("read result set jdbc type metadata failed for column {}", columnIndex, e);
        }

        try {
            // Field.nullable is boolean, so failed/unknown nullable metadata remains false.
            field.setNullable(metaData.isNullable(columnIndex) == ResultSetMetaData.columnNullable);
        } catch (SQLException e) {
            log.debug("read result set nullable metadata failed for column {}", columnIndex, e);
        }
        try {
            field.setAutoIncrement(metaData.isAutoIncrement(columnIndex));
        } catch (Exception e) {
            log.debug("read result set auto increment metadata failed for column {}", columnIndex, e);
        }
        try {
            field.setReadOnly(metaData.isReadOnly(columnIndex));
        } catch (Exception e) {
            log.debug("read result set read only metadata failed for column {}", columnIndex, e);
        }
        try {
            if (!metaData.isWritable(columnIndex)) {
                field.setReadOnly(true);
            }
        } catch (Exception e) {
            log.debug("read result set writable metadata failed for column {}", columnIndex, e);
        }
    }

    private static String getNullableMetadataValue(MetadataValueReader reader, String name, int columnIndex) {
        try {
            String value = reader.read();
            return StringUtils.isNotBlank(value) ? value : null;
        } catch (SQLException e) {
            log.debug("read result set {} metadata failed for column {}", name, columnIndex, e);
            return null;
        }
    }

    @FunctionalInterface
    private interface MetadataValueReader {
        String read() throws SQLException;
    }

    // Normalize JDBC driver objects before FastJSON sees them in update_data_view packets.
    protected Object normalizeJdbcValue(Object value) throws SQLException {
        Object normalized = normalizeJdbcDisplayValue(value);
        if (normalized != value) {
            return normalized;
        }
        if (value == null) {
            return null;
        }

        if (value instanceof Long || value instanceof BigDecimal || value instanceof BigInteger) {
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
            return HexUtils.bytesToHex(blob.getBytes(1, (int) blob.length()));
        }

        if (value.getClass().getSimpleName().equalsIgnoreCase("pgobject")) {
            return value.toString();
        }

        return value;
    }

    static Object normalizeJdbcDisplayValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            var localDateTime = timestamp.toLocalDateTime();
            if (timestamp.getNanos() == 0) {
                return localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.n"));
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        return value;
    }

    static void markGeneratedColumns(Connection connection, DbType dbType, List<Field> fields) {
        if (dbType != DbType.postgresql || connection == null || fields == null || fields.isEmpty()) {
            return;
        }
        String sql = """
                SELECT is_generated, generation_expression
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                """;
        for (Field field : fields) {
            if (field == null ||
                    StringUtils.isBlank(field.getSchema()) ||
                    StringUtils.isBlank(field.getTable()) ||
                    StringUtils.isBlank(field.getColumnName())) {
                continue;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, field.getSchema());
                statement.setString(2, field.getTable());
                statement.setString(3, field.getColumnName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        continue;
                    }
                    String isGenerated = resultSet.getString("is_generated");
                    String generationExpression = resultSet.getString("generation_expression");
                    if ("ALWAYS".equalsIgnoreCase(isGenerated) || StringUtils.isNotBlank(generationExpression)) {
                        field.setGenerated(true);
                        field.setReadOnly(true);
                    }
                }
            } catch (SQLException e) {
                log.debug("read postgresql generated column metadata failed for {}.{}.{}",
                        field.getSchema(),
                        field.getTable(),
                        field.getColumnName(),
                        e);
            }
        }
    }

    private String toDisplayArray(java.sql.Array jdbcArray) throws SQLException {
        try {
            var text = jdbcArray.toString();
            // Prefer driver-provided array text when it is not the default Object.toString() form.
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
            // byte[] is also an array; handle it before recursive array formatting.
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

    private void handleDataMasking(SQLQueryResult result) {
        var rules = SessionManager.getCurrentSession().getDataMaskingRules();
        var maskIndexes = new ArrayList<>();
        var maskRules = new HashMap<Integer, Common.DataMaskingRule>();
        for (var i = 0; i < result.getFields().size(); i++) {
            var field = result.getFields().get(i);
            for (Common.DataMaskingRule rule : rules) {
                if (this.matchField(field, rule.getFieldsPattern())) {
                    field.setMasked(true);
                    field.setEditable(false);
                    field.setEditReason(EditabilityReason.DATA_MASKED);
                    field.setInsertable(false);
                    field.setInsertReason(EditabilityReason.DATA_MASKED);
                    maskIndexes.add(i);
                    maskRules.put(i, rule);
                }
            }
        }

        for (var i = 0; i < result.getData().size(); i++) {
            for (var j = 0; j < result.getData().get(i).size(); j++) {
                if (maskIndexes.contains(j)) {
                    var rule = maskRules.get(j);
                    var val = result.getData().get(i).get(j);
                    if (val instanceof String) {
                        var rep = this.replaceColumnVal(rule, (String) val);
                        result.getData().get(i).set(j, rep);
                    } else {
                        result.getData().get(i).set(j, rule.getMaskPattern());
                    }
                }
            }
        }
    }

    private boolean matchField(Field field, String pattern) {
        List<String> names = List.of(field.getColumnName(), field.getLabel());
        String[] ps = pattern.split(",");

        for (String name : names) {
            for (String p : ps) {
                p = p.trim();
                if (p.isEmpty()) continue;

                try {
                    // 先整体转义，避免用户写的正则符号被误解释
                    String regex = Pattern.quote(p);
                    // 把被转义的 \* 恢复为 .*
                    regex = regex.replace("\\*", ".*");
                    // 加上锚点，实现整串匹配
                    regex = "^" + regex + "$";

                    int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    Pattern pa = Pattern.compile(regex, flags);

                    if (pa.matcher(name).matches()) { // 注意：用 matches() 而不是 find()
                        return true;
                    }
                } catch (PatternSyntaxException e) {
                    // 忽略坏模式，继续下一个
                    continue;
                }
            }
        }
        return false;
    }


    private String replaceColumnVal(Common.DataMaskingRule rule, String val) {
        if (rule == null) {
            return "####";
        } else {
            rule.getMaskingMethod();
        }

        String method = rule.getMaskingMethod();
        rule.getMaskPattern();
        String pattern = rule.getMaskPattern();

        switch (method) {
            case "fixed_char":
                // 固定字符替换
                if (pattern.isEmpty()) {
                    return "####";
                }
                return pattern;

            case "hide_middle":
                // 隐藏中间
                if (val == null || val.length() < 3) {
                    return pattern.isEmpty() ? "####" : pattern;
                }
                return val.charAt(0)
                        + "*".repeat(val.length() - 2)
                        + val.substring(val.length() - 1);

            case "keep_prefix":
                // 保留前缀
                int prefix = 2;
                if (val == null || prefix >= val.length()) {
                    return "####";
                }
                return val.substring(0, prefix)
                        + "*".repeat(val.length() - prefix);

            case "keep_suffix":
                // 保留后缀
                int suffix = 2;
                if (val == null || suffix >= val.length()) {
                    return "####";
                }
                return "*".repeat(val.length() - suffix)
                        + val.substring(val.length() - suffix);

            default:
                // 未知策略
                return pattern.isEmpty() ? "####" : pattern;
        }
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

    @Override
    public SQLQueryResult executeRawWithAudit(SQLExecutePlan plan) throws SQLException {
        var sess = SessionManager.getCurrentSession();
        try {
            return sess.withAudit(plan.getTargetSQL(), () -> this.executeRaw(plan));
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
