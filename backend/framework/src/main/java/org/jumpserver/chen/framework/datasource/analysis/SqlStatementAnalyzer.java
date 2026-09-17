package org.jumpserver.chen.framework.datasource.analysis;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExplainStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitor;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only Druid AST analysis. No Connection, catalog lookup, execution, or SQL rewrite.
 */
public final class SqlStatementAnalyzer {
    public static final String MULTI_STATEMENT = "MULTI_STATEMENT";
    public static final String PARSE_FAILED = "PARSE_FAILED";
    public static final String EMPTY_SQL = "EMPTY_SQL";
    public static final String NOT_SINGLE_SELECT = "NOT_SINGLE_SELECT";
    public static final String NESTED_WRITE = "NESTED_WRITE";
    public static final String LOCKING_READ = "LOCKING_READ";
    public static final String SELECT_INTO = "SELECT_INTO";
    public static final String ASSIGNMENT = "ASSIGNMENT";
    public static final String EXPLAIN_WRAPPER = "EXPLAIN_WRAPPER";
    public static final String UNBOUND_PARAMETER = "UNBOUND_PARAMETER";
    public static final String DYNAMIC_EXECUTION = "DYNAMIC_EXECUTION";

    private static final Pattern TRAILING_DELIMITER = Pattern.compile(";\\s*$");
    private static final Pattern EXECUTABLE_COMMENT = Pattern.compile("/\\*!\\d*.*?\\*/", Pattern.DOTALL);
    private static final Pattern PREPARE_EXECUTE = Pattern.compile(
            "(?i)\\b(execute\\s+immediate|execute\\s+procedure|prepare\\s+\\S+\\s+as|call\\s+\\S+\\s*\\()\\b"
    );
    // PostgreSQL CTE materialization (AS [NOT] MATERIALIZED (...)) is not accepted by Druid 1.2.28
    // after AS. Strip only that optional clause for parse; keep the original SQL for EXPLAIN.
    private static final Pattern CTE_MATERIALIZED = Pattern.compile(
            "(?i)(\\bAS)\\s+(NOT\\s+)?MATERIALIZED\\s*(\\()"
    );

    private SqlStatementAnalyzer() {
    }

    public static SqlStatementAnalysis analyze(DbType dbType, String sql) {
        if (StringUtils.isBlank(sql)) {
            return rejected("", StatementKind.OTHER, null, false, List.of(), List.of(EMPTY_SQL));
        }

        String original = stripTrailingDelimiter(sql.strip());
        if (original.isEmpty()) {
            return rejected("", StatementKind.OTHER, null, false, List.of(), List.of(EMPTY_SQL));
        }

        List<SQLStatement> statements;
        try {
            statements = parseStatements(dbType, original);
        } catch (RuntimeException e) {
            return rejected(original, StatementKind.OTHER, null, false, List.of(), List.of(PARSE_FAILED));
        }

        if (statements == null || statements.isEmpty()) {
            return rejected(original, StatementKind.OTHER, null, false, List.of(), List.of(EMPTY_SQL));
        }
        if (statements.size() != 1) {
            return rejected(original, kindOf(statements.get(0)), statements.get(0), false, List.of(), List.of(MULTI_STATEMENT));
        }

        SQLStatement statement = statements.get(0);
        StatementKind kind = kindOf(statement);
        FactsVisitor visitor = new FactsVisitor();
        statement.accept(visitor);

        List<String> codes = new ArrayList<>();
        boolean explainWrapper = statement instanceof SQLExplainStatement || visitor.explainWrapper;
        if (kind != StatementKind.SELECT) {
            codes.add(kind == StatementKind.EXPLAIN ? EXPLAIN_WRAPPER : NOT_SINGLE_SELECT);
        }
        if (explainWrapper) {
            codes.add(EXPLAIN_WRAPPER);
        }
        if (visitor.nestedWrite) {
            codes.add(NESTED_WRITE);
        }
        if (visitor.lockingRead) {
            codes.add(LOCKING_READ);
        }
        if (visitor.selectInto) {
            codes.add(SELECT_INTO);
        }
        if (visitor.assignment) {
            codes.add(ASSIGNMENT);
        }
        if (visitor.unboundParameter) {
            codes.add(UNBOUND_PARAMETER);
        }
        if (visitor.dynamicExecution || looksDynamic(original) || hasExecutableComment(original)) {
            codes.add(DYNAMIC_EXECUTION);
        }

        return new SqlStatementAnalysis(
                original,
                kind,
                statement,
                true,
                visitor.nestedWrite,
                visitor.lockingRead,
                visitor.selectInto,
                visitor.assignment,
                explainWrapper,
                visitor.unboundParameter,
                codes.contains(DYNAMIC_EXECUTION),
                List.copyOf(visitor.functionNames),
                List.copyOf(new LinkedHashSet<>(codes))
        );
    }

    static String stripTrailingDelimiter(String sql) {
        return TRAILING_DELIMITER.matcher(sql).replaceFirst("");
    }

    static String sqlForParse(String sql) {
        return CTE_MATERIALIZED.matcher(sql).replaceAll("$1 $3");
    }

    private static List<SQLStatement> parseStatements(DbType dbType, String sql) {
        try {
            return SQLUtils.parseStatements(sql, dbType);
        } catch (RuntimeException originalError) {
            String parseSql = sqlForParse(sql);
            if (parseSql.equals(sql)) {
                throw originalError;
            }
            return SQLUtils.parseStatements(parseSql, dbType);
        }
    }

    private static boolean hasExecutableComment(String sql) {
        return EXECUTABLE_COMMENT.matcher(sql).find();
    }

    private static boolean looksDynamic(String sql) {
        return PREPARE_EXECUTE.matcher(sql).find();
    }

    private static StatementKind kindOf(SQLStatement statement) {
        if (statement instanceof SQLExplainStatement) {
            return StatementKind.EXPLAIN;
        }
        if (statement instanceof SQLSelectStatement) {
            return StatementKind.SELECT;
        }
        if (statement instanceof SQLInsertStatement
                || statement instanceof SQLUpdateStatement
                || statement instanceof SQLDeleteStatement
                || statement instanceof SQLMergeStatement
                || statement instanceof SQLReplaceStatement) {
            return StatementKind.DML;
        }
        String type = statement.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("create") || type.contains("alter") || type.contains("drop") || type.contains("truncate")) {
            return StatementKind.DDL;
        }
        return StatementKind.OTHER;
    }

    private static SqlStatementAnalysis rejected(
            String sql,
            StatementKind kind,
            SQLStatement ast,
            boolean singleStatement,
            List<String> functionNames,
            List<String> codes
    ) {
        return new SqlStatementAnalysis(
                sql,
                kind,
                ast,
                singleStatement,
                false,
                false,
                false,
                false,
                codes.contains(EXPLAIN_WRAPPER),
                false,
                false,
                functionNames,
                codes
        );
    }

    private static final class FactsVisitor extends SQLASTVisitorAdapter implements OracleASTVisitor {
        private boolean nestedWrite;
        private boolean lockingRead;
        private boolean selectInto;
        private boolean assignment;
        private boolean explainWrapper;
        private boolean unboundParameter;
        private boolean dynamicExecution;
        private final Set<String> functionNames = new LinkedHashSet<>();

        @Override
        public boolean visit(SQLExplainStatement x) {
            explainWrapper = true;
            return true;
        }

        @Override
        public boolean visit(SQLInsertStatement x) {
            nestedWrite = true;
            return true;
        }

        @Override
        public boolean visit(SQLUpdateStatement x) {
            nestedWrite = true;
            return true;
        }

        @Override
        public boolean visit(SQLDeleteStatement x) {
            nestedWrite = true;
            return true;
        }

        @Override
        public boolean visit(SQLMergeStatement x) {
            nestedWrite = true;
            return true;
        }

        @Override
        public boolean visit(SQLReplaceStatement x) {
            nestedWrite = true;
            return true;
        }

        @Override
        public boolean visit(SQLSetStatement x) {
            assignment = true;
            return true;
        }

        @Override
        public boolean visit(SQLAssignItem x) {
            assignment = true;
            return true;
        }

        @Override
        public void preVisit(SQLObject x) {
            if (x instanceof SQLSelectQueryBlock block) {
                inspectQueryBlock(block);
            }
        }

        @Override
        public boolean visit(SQLSelectQueryBlock x) {
            inspectQueryBlock(x);
            return true;
        }

        private void inspectQueryBlock(SQLSelectQueryBlock x) {
            if (x.isForUpdate() || x.isForShare() || x.getForUpdateOfSize() > 0) {
                lockingRead = true;
            }
            if (x.getInto() != null) {
                selectInto = true;
            }
            if (x instanceof PGSelectQueryBlock pg) {
                if (pg.getForClause() != null) {
                    lockingRead = true;
                }
                if (pg.getIntoOption() != null) {
                    selectInto = true;
                }
            }
        }

        @Override
        public boolean visit(SQLMethodInvokeExpr x) {
            String name = x.getMethodName();
            if (StringUtils.isNotBlank(name)) {
                String owner = x.getOwner() == null ? "" : x.getOwner().toString();
                functionNames.add(owner.isBlank() ? name : owner + "." + name);
            }
            return true;
        }

        @Override
        public boolean visit(SQLVariantRefExpr x) {
            String name = x.getName();
            if (StringUtils.isBlank(name) || "?".equals(name) || name.startsWith(":") || name.startsWith("$")) {
                unboundParameter = true;
            }
            return true;
        }
    }
}
