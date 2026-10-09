package org.jumpserver.chen.framework.datasource;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.session.QueryAuditFunction;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;

/** Runs only against a disposable in-memory H2 database, supplied on the classpath. */
public class PropertyQuerySecurityCheck {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        Session session = proxy(Session.class, (p, m, a) -> switch (m.getName()) {
            case "getDataMaskingRules" -> List.of();
            case "withAudit" -> ((QueryAuditFunction) a[1]).run();
            default -> null;
        });
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:property_check;MODE=MySQL")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE metadata (name VARCHAR(255), amount BIGINT)");
            }
            List<String> names = List.of("normal", "O'Reilly", "' OR 1=1 --", "'; DROP TABLE metadata; --", "back\\slash", "中文");
            for (String name : names) {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO metadata VALUES (?, ?)")) {
                    statement.setString(1, name);
                    statement.setLong(2, 1234567890123L);
                    statement.executeUpdate();
                }
            }
            Datasource datasource = proxy(Datasource.class, (p, m, a) -> DbType.mysql);
            ConnectionManager manager = proxy(ConnectionManager.class, (p, m, a) -> switch (m.getName()) {
                case "getDatasource" -> datasource;
                case "getConnection" -> connection;
                default -> null;
            });
            BaseSQLActuator actuator = new BaseSQLActuator(manager) {
                public String getCurrentSchema() { return ""; }
                public List<String> getSchemas() { return List.of(); }
                public void changeSchema(String schema) {}
                public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams params) {
                    throw new UnsupportedOperationException();
                }
            };
            // Establish the original boundary failure with a read-only query.
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(SQL.of("SELECT name FROM metadata WHERE name = '?'", "' OR 1=1 --").getSql())) {
                int count = 0;
                while (result.next()) count++;
                if (count != names.size()) throw new AssertionError("baseline injection not reproduced");
            }
            for (String name : names) {
                SQL query = SQL.bound("SELECT name, amount FROM metadata WHERE name = ?", name);
                SQLQueryParams page = new SQLQueryParams();
                page.setLimit(1);
                SQLExecutePlan plan = actuator.createPlan(query, page);
                query.getParameters().set(0, "changed_after_plan");
                SQLQueryResult result = plan.execute();
                if (result.getTotal() != 1 || !result.isPaged() || result.getData().size() != 1
                        || !name.equals(result.getData().get(0).get(0))
                        || !"1234567890123".equals(result.getData().get(0).get(1))) {
                    throw new AssertionError("binding, count, paging or field conversion changed");
                }
                if (!result.getFields().get(0).getName().equalsIgnoreCase("name")) {
                    throw new AssertionError("result field label changed");
                }
                SQLQueryResult unpaged = actuator.execute(SQL.bound("SELECT name FROM metadata WHERE name = ?", name));
                if (unpaged.getData().size() != 1 || !name.equals(unpaged.getData().get(0).get(0))) {
                    throw new AssertionError("property execute path changed");
                }
            }
            SQLQueryResult plain = actuator.execute(SQL.of("SELECT name FROM metadata"));
            if (plain.getTotal() != names.size() || plain.getData().size() != names.size()) {
                throw new AssertionError("ordinary unbound SQL changed or table was modified");
            }
            SQLQueryResult audited = actuator.executeWithAudit(SQL.bound("SELECT name FROM metadata WHERE name = ?", "normal"));
            if (audited.getData().size() != 1) throw new AssertionError("audit execution lost binding");
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
        }
        System.out.println("Property binding, count, paging, result conversion and ordinary SQL controls passed (in-memory H2)");
    }
}
