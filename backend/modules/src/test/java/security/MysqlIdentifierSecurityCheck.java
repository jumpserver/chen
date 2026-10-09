package security;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.modules.mysql.MysqlActuator;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.function.Function;

public class MysqlIdentifierSecurityCheck {
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static ConnectionManager manager(DbType dbType, Connection connection) {
        Datasource datasource = proxy(Datasource.class, (p, m, a) -> dbType);
        return proxy(ConnectionManager.class, (p, m, a) -> switch (m.getName()) {
            case "getDatasource" -> datasource;
            case "getConnection" -> connection;
            default -> null;
        });
    }

    static void checkViews(DbType dbType, String mode, Function<ConnectionManager, BaseSQLActuator> factory,
                           Function<String, String> quote) throws Exception {
        Class.forName("org.h2.Driver");
        Session session = proxy(Session.class, (p, m, a) -> m.getName().equals("getDataMaskingRules") ? List.of() : null);
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + dbType + "_names;MODE=" + mode)) {
            BaseSQLActuator actuator = factory.apply(manager(dbType, connection));
            List<String> names = List.of("normal", "MixedCase", "中文", "with space", "with.dot", "back\\slash",
                    "O'Reilly", "tick`name", "quote\"name", "close]name", "t` UNION SELECT 99 -- ", "t\" WHERE 1=1 -- ");
            for (int index = 0; index < names.size(); index++) {
                String schema = names.get(index);
                String table = names.get(names.size() - 1 - index);
                String target = quote.apply(schema) + "." + quote.apply(table);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + quote.apply(schema));
                    statement.execute("CREATE TABLE " + target + " (marker INT)");
                    statement.execute("INSERT INTO " + target + " VALUES (7)");
                }
                SQLExecutePlan plan = actuator.createPlan(schema, table, null);
                var statements = SQLUtils.parseStatements(plan.getSourceSQL(), dbType);
                if (statements.size() != 1 || !(statements.get(0) instanceof SQLSelectStatement select)
                        || select.getSelect().getQueryBlock().getWhere() != null) {
                    throw new AssertionError("identifier escaped into SQL syntax");
                }
                SQLQueryParams page = new SQLQueryParams();
                page.setLimit(1);
                plan.setSqlQueryParams(page);
                plan.generateTargetSQL();
                SQLQueryResult result = plan.execute();
                if (result.getTotal() != 1 || result.getData().size() != 1 || !Integer.valueOf(7).equals(result.getData().get(0).get(0))) {
                    throw new AssertionError("metadata name, paging/count or result changed");
                }
            }
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
        }
    }

    public static void main(String[] args) throws Exception {
        checkViews(DbType.mysql, "MySQL", MysqlActuator::new, SQLIdentifier::mysql);
        String[] executed = new String[1];
        MysqlActuator actuator = new MysqlActuator(manager(DbType.mysql, null)) {
            public SQLQueryResult execute(SQL sql) { executed[0] = sql.getSql(); return new SQLQueryResult(sql.getSql()); }
        };
        for (String database : List.of("normal", "中文", "O'Reilly", "tick`name", "db`; SELECT 1; -- ", "db\\`tail")) {
            actuator.changeSchema(database);
            if (SQLUtils.parseStatements(executed[0], DbType.mysql).size() != 1) throw new AssertionError("USE injection");
        }
        actuator.changeSchema("normal");
        if (!"use `normal`".equals(executed[0])) throw new AssertionError("normal USE changed");
        System.out.println("MySQL name containment and actual actuator paging/count passed (H2 MySQL mode; USE parser/capture)");
    }
}
