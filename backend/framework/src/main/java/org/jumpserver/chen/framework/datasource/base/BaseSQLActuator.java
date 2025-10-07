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
import org.jumpserver.wisp.Common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class BaseSQLActuator implements SQLActuator {

    @Getter
    private final DbType druidDbType;
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

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    Field field = new Field();

                    var fieldName = StringUtils.isNotEmpty(resultSet.getMetaData().getColumnLabel(i)) ?
                            resultSet.getMetaData().getColumnLabel(i) : resultSet.getMetaData().getColumnName(i);
                    field.setName(fieldName);
                    result.getFields().add(field);
                }

                while (resultSet.next()) {
                    List<Object> fs = new ArrayList<>();
                    for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                        try {
                            var obj = resultSet.getObject(i);
                            if (obj instanceof Timestamp timestamp) {
                                fs.add(new Date(timestamp.getTime()));
                            } else if (obj instanceof Long || obj instanceof BigDecimal || obj instanceof BigInteger) {
                                fs.add(obj.toString());
                            } else if (obj instanceof byte[]) {
                                fs.add(HexUtils.bytesToHex((byte[]) obj));
                            } else if (obj instanceof Blob) {
                                fs.add(HexUtils.bytesToHex(((Blob) obj).getBytes(1, (int) ((Blob) obj).length())));
                            } else if (obj != null && obj.getClass().getSimpleName().equalsIgnoreCase("pgobject")) {
                                fs.add(obj.toString());
                            } else {
                                fs.add(obj);
                            }
                        } catch (NoClassDefFoundError e) {
                            log.error(e.getMessage());
                        }
                    }
                    result.getData().add(fs);
                }
                resultSet.close();
                result.setFetchFinishedTime(new Time(System.currentTimeMillis()));

                // 数据脱敏

                var rules = SessionManager.getCurrentSession().getDataMaskingRules();
                var maskIndexes = new ArrayList<>();
                var maskRules = new HashMap<Integer, Common.DataMaskingRule>();
                for (var i = 0; i < result.getFields().size(); i++) {
                    for (Common.DataMaskingRule rule : rules) {
                        if (result.getFields().get(i).getName().equalsIgnoreCase(rule.getFieldsPattern())) {
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



    public  String replaceColumnVal(Common.DataMaskingRule rule, String val) {
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
