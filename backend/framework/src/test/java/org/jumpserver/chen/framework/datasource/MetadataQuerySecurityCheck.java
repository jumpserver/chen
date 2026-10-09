package org.jumpserver.chen.framework.datasource;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/** Runnable JDBC boundary check; no database or application session is required. */
public class MetadataQuerySecurityCheck {
    public static class Row { public String name; public void setName(String name) { this.name = name; } }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    public static void main(String[] args) throws Exception {
        for (String value : List.of("public", "O'Reilly", "\\' OR 1=1 --", "'; DROP TABLE x; --", "中文")) {
            List<Object> bound = new ArrayList<>();
            String[] prepared = new String[1];
            int[] row = {0};
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p, m, a) -> {
                if (m.getName().equals("getColumnCount")) return 1;
                return null;
            });
            ResultSet result = proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
                case "next" -> row[0]++ == 0;
                case "getMetaData" -> metadata;
                case "getObject" -> value;
                default -> null;
            });
            PreparedStatement statement = proxy(PreparedStatement.class, (p, m, a) -> {
                if (m.getName().equals("setObject")) { bound.add(a[1]); return null; }
                if (m.getName().equals("executeQuery")) {
                    if (a != null && a.length != 0) throw new AssertionError("SQL flattened at execution");
                    return result;
                }
                return null;
            });
            Connection connection = proxy(Connection.class, (p, m, a) -> {
                if (m.getName().equals("prepareStatement")) { prepared[0] = (String)a[0]; return statement; }
                if (m.getName().equals("createStatement")) throw new AssertionError("unbound statement");
                return null;
            });
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
                public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams params) { throw new UnsupportedOperationException(); }
            };
            String query = "SELECT name FROM metadata WHERE schema_name = ?";
            List<Row> rows = actuator.getObjects(SQL.bound(query, value), Row.class, Map.of("name", 1));
            if (!query.equals(prepared[0]) || !bound.equals(List.of(value)) || !rows.get(0).name.equals(value)) {
                throw new AssertionError("query/binding/result mapping changed");
            }
        }
        System.out.println("Metadata JDBC binding controls passed");
    }
}
