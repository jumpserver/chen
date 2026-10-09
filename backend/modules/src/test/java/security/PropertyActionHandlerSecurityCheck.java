package security;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseActionHandler;
import org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailDialog;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.modules.mysql.MysqlActuator;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;

public class PropertyActionHandlerSecurityCheck {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    public static void main(String[] args) throws Exception {
        SQL[] captured = new SQL[1];
        Datasource dialect = proxy(Datasource.class, (p, m, a) -> DbType.mysql);
        var initialManager = proxy(org.jumpserver.chen.framework.datasource.ConnectionManager.class,
                (p, m, a) -> m.getName().equals("getDatasource") ? dialect : null);
        MysqlActuator actuator = new MysqlActuator(initialManager) {
            public SQLQueryResult execute(SQL sql) {
                captured[0] = sql;
                SQLQueryResult result = new SQLQueryResult(sql.getSql());
                Field field = new Field(); field.setName("name");
                result.getFields().add(field);
                result.getData().add(List.of(sql.getParameters().get(0)));
                return result;
            }
        };
        var manager = proxy(org.jumpserver.chen.framework.datasource.ConnectionManager.class,
                (p, m, a) -> m.getName().equals("getSqlActuator") ? actuator : null);
        Datasource datasource = proxy(Datasource.class,
                (p, m, a) -> m.getName().equals("getConnectionManager") ? manager : null);
        Session session = proxy(Session.class,
                (p, m, a) -> m.getName().equals("getDatasource") ? datasource : null);
        String token = SessionManager.registerSession(session); SessionManager.setContext(token);
        int templates = 0;
        try {
            for (String className : List.of("mysql.MysqlActionHandler", "postgresql.PostgresqlActionHandler", "sqlserver.SQLServerActionHandler",
                    "oracle.OracleActionHandler", "db2.DB2ActionHandler", "dameng.DMActionHandler", "clickhouse.ClickhouseActionHandler")) {
                Class<?> cls = Class.forName("org.jumpserver.chen.modules." + className);
                BaseActionHandler handler = (BaseActionHandler) cls.getConstructor().newInstance();
                for (var field : cls.getDeclaredFields()) {
                    if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith("SQL_SELECT_")) continue;
                    field.setAccessible(true); String template = (String) field.get(null); templates++;
                    if (template.contains("'?'") || template.chars().filter(c -> c == '?').count() != 1) throw new AssertionError("unbound property template");
                    for (String name : List.of("normal", "O'Reilly", "' OR 1=1 --", "back\\slash", "中文")) {
                        TreeNode node = new TreeNode(); node.setKey("table:" + name);
                        var event = handler.onShowObjectProperties("table", template, node);
                        if (!template.equals(captured[0].getSql()) || !List.of(name).equals(captured[0].getParameters())) {
                            throw new AssertionError("node value became SQL syntax or was changed");
                        }
                        DetailDialog dialog = (DetailDialog) event.getData();
                        if (!"new_dialog".equals(event.getEvent()) || dialog.getItems().size() != 1
                                || !name.equals(dialog.getItems().get(0).getValue())) throw new AssertionError("property dialog changed");
                    }
                }
            }
            if (templates != 11) throw new AssertionError("property template coverage changed");
        } finally { SessionManager.unregisterSession(token); SessionManager.setContext(null); }
        System.out.println("All 11 property templates across 7 dialect handlers bind node values and preserve dialog fields (isolated actuator capture)");
    }
}
