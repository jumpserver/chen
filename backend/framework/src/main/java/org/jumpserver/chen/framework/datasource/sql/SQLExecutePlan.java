package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.utils.PageUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;


@Setter
@Getter
@Slf4j
public class SQLExecutePlan {
    private final String sourceSQL;

    private SQLActuator sqlActuator;
    private String targetSQL;
    private final DbType druidDbType;
    private Statement statement;
    private Connection connection;
    private ACLResult aclResult;


    private boolean counted;

    private SQLQueryParams sqlQueryParams = new SQLQueryParams();


    public SQLExecutePlan(String sql, DbType druidDbType) {
        this.sourceSQL = sql;
        this.targetSQL = sql;
        this.druidDbType = druidDbType;
    }

    public void generateTargetSQL() throws SQLException {
        this.targetSQL = this.sourceSQL;

        if (this.sqlQueryParams == null) {
            return;
        }

        if (this.sqlQueryParams.getLimit() == -1) {
            return;
        }

        if (this.getTargetSQLStatement() instanceof SQLSelectStatement selectStatement) {
            if (PageUtils.getLimit(this.targetSQL, this.druidDbType) > -1) {
                return;
            }
            this.targetSQL = PageUtils.limit(selectStatement.toString(),
                    this.druidDbType,
                    this.getSqlQueryParams().getOffset(),
                    this.getSqlQueryParams().getLimit());

            if (this.getSqlQueryParams().getOffset() < 0) {
                this.close();
                throw new SQLException(MessageUtils.get("AlreadyFirstPageError"));
            }

            var count = this.sqlActuator.count(this);
            if (count > 0 && this.getSqlQueryParams().getOffset() >= count) {
                this.close();
                throw new SQLException(MessageUtils.get("AlreadyLastPageError"));
            }
        }
    }


    public SQLQueryResult execute() throws SQLException {
        return this.sqlActuator.execute(this);
    }

    public SQLQueryResult executeWithAudit() throws SQLException {
        return this.sqlActuator.executeWithAudit(this);
    }


    public Statement createStatement() throws SQLException {
        if (this.statement == null || this.statement.isClosed()) {
            this.statement = this.connection.createStatement();
        }
        return this.statement;
    }

    public SQLStatement getTargetSQLStatement() {
        return SQLUtils.parseSingleStatement(this.targetSQL, this.druidDbType.name());
    }

    public void cancel() throws SQLException {
        this.statement.cancel();
    }

    public void close() {
        try {
            if (this.connection instanceof DruidPooledConnection) {
                this.connection.close();
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }
    }

}
