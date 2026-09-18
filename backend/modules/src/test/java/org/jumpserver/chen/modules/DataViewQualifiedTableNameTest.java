package org.jumpserver.chen.modules;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.junit.Test;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.jumpserver.chen.modules.dameng.DMActuator;
import org.jumpserver.chen.modules.db2.DB2Actuator;
import org.jumpserver.chen.modules.oracle.OracleActuator;
import org.jumpserver.chen.modules.sqlserver.SQLServerActuator;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * DataView 打开表时按 schema/table 生成 {@code select * from <qualified table>}。
 * 未加引号的标识符一旦含 {@code #}(如达梦内部表 {@code ##HISTOGRAMS_TABLE})，Druid 会把
 * {@code ##} 词法解析成 VARIANT，抛 ParserException 并连带关掉整个 console websocket。
 * 这里锁定各方言都必须产出自身可解析的带引号限定名。
 */
public class DataViewQualifiedTableNameTest {

    private record Dialect(String name, DbType dbType, Function<ConnectionManager, SQLActuator> factory,
                           Function<String, String> expectQuoted) {
    }

    private static final List<Dialect> DIALECTS = List.of(
            new Dialect("dm", DbType.dm, DMActuator::new, name -> "\"" + name + "\""),
            new Dialect("db2", DbType.db2, DB2Actuator::new, name -> "\"" + name + "\""),
            new Dialect("oracle", DbType.oracle, OracleActuator::new, name -> "\"" + name + "\""),
            new Dialect("sqlserver", DbType.sqlserver, SQLServerActuator::new, name -> "[" + name + "]")
    );

    @Test
    public void generatesQuotedQualifiedNameForOrdinaryTable() throws Exception {
        assertQualifiedName("SYSDBA", "ORDERS", "select * from %s.%s");
    }

    /**
     * 回归用例：达梦内部表 {@code ##HISTOGRAMS_TABLE} 修复前生成
     * {@code select * from SYSDBA.##HISTOGRAMS_TABLE}，在 column 22 处解析失败。
     */
    @Test
    public void generatesParseableQualifiedNameForHashPrefixedTable() throws Exception {
        assertQualifiedName("SYSDBA", "##HISTOGRAMS_TABLE", "select * from %s.%s");
    }

    /** 关键字表名、大小写敏感表名与特殊字符表名同样必须被正确引用。 */
    @Test
    public void quotesKeywordCaseSensitiveAndSpecialCharacterNames() throws Exception {
        assertQualifiedName("SYSDBA", "ORDER", "select * from %s.%s");
        assertQualifiedName("SYSDBA", "MixedCaseTable", "select * from %s.%s");
        assertQualifiedName("SYSDBA", "with space", "select * from %s.%s");
        assertQualifiedName("SYSDBA", "semi;colon--", "select * from %s.%s");
    }

    /**
     * 标识符内部的引号字符必须按各方言规则转义，不能提前闭合引用。
     * 注：SQL Server 的 {@code []]} 转义是本仓库内嵌 Druid 的词法限制（既有 SQLIdentifier 行为，
     * 与本次 # 号修复无关），故此处只对采用 {@code ""} 双写转义的方言断言可 round-trip。
     */
    @Test
    public void escapesEmbeddedQuoteCharacters() throws Exception {
        for (var dialect : DIALECTS) {
            if (dialect.dbType() == DbType.sqlserver) {
                continue; // 已知 Druid 限制：见方法注释
            }
            var actuator = dialect.factory().apply(connectionManager(dialect.dbType()));
            var expected = "\"we\"\"ird\"";

            var plan = actuator.createPlan("SYSDBA", "we\"ird", null);
            var sql = plan.getTargetSQL();

            assertTrue(dialect.name() + " should escape embedded quote: " + sql, sql.contains(expected));
            assertParses(dialect, sql);
        }
    }

    private static void assertQualifiedName(String schema, String table, String template) throws Exception {
        for (var dialect : DIALECTS) {
            var actuator = dialect.factory().apply(connectionManager(dialect.dbType()));

            // 与 DataViewConsole.createDataView 相同的调用方式：先取未加 limit 的基准 SQL。
            var plan = actuator.createPlan(schema, table, null);
            var sql = plan.getTargetSQL();

            assertEquals(dialect.name(),
                    String.format(template,
                            dialect.expectQuoted().apply(schema),
                            dialect.expectQuoted().apply(table)),
                    sql);

            // 修复前此处抛 ParserException: pos .., token VARIANT
            assertParses(dialect, sql);

            // DataView 分页会重新解析并回写 SQL，引用必须在 round-trip 中保留。
            var paged = PageUtils.limit(sql, dialect.dbType(), 0, 50);
            assertTrue(dialect.name() + " lost quoting while paging: " + paged,
                    paged.contains(dialect.expectQuoted().apply(table)));
            assertParses(dialect, paged);
        }
    }

    private static void assertParses(Dialect dialect, String sql) {
        SQLUtils.parseSingleStatement(sql, dialect.dbType().name());
    }

    private static ConnectionManager connectionManager(DbType dbType) {
        var datasource = (Datasource) Proxy.newProxyInstance(
                Datasource.class.getClassLoader(),
                new Class[]{Datasource.class},
                (proxy, method, args) -> "getDruidDbType".equals(method.getName()) ? dbType : null
        );
        return (ConnectionManager) Proxy.newProxyInstance(
                ConnectionManager.class.getClassLoader(),
                new Class[]{ConnectionManager.class},
                (proxy, method, args) -> "getDatasource".equals(method.getName()) ? datasource : null
        );
    }
}
