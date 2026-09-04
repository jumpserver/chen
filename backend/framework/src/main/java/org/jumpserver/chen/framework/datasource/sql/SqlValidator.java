package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SqlValidator {
    public static final String QUERY_UNSUPPORTED_MESSAGE =
            "This SQL is not supported by Query because Chen cannot parse it with Druid. Use Console to draft and run it.";

    private static final int MAX_OBJECTS = 50;
    private static final int MAX_ANALYSIS_COLUMNS = 512;

    private SqlValidator() {
    }

    public static List<SQLStatement> parse(DbType dbType, String sql) {
        return SQLUtils.parseStatements(sql, dbType);
    }

    public static SqlValidationResult validate(DbType dbType, String sql) {
        if (StringUtils.isBlank(sql)) {
            return unparseable("SQL statement is empty");
        }

        List<SQLStatement> statements;
        try {
            statements = parse(dbType, sql);
            if (statements == null || statements.isEmpty()) {
                return unparseable("SQL statement is empty");
            }
        } catch (RuntimeException e) {
            return unparseable(safeError(e));
        }

        LinkedHashSet<String> tables = new LinkedHashSet<>();
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        String statementType = statements.size() == 1 ? statementType(statements.get(0)) : "MULTI";
        for (SQLStatement statement : statements) {
            try {
                SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(dbType);
                statement.accept(visitor);
                for (TableStat.Name table : visitor.getTables().keySet()) {
                    addBounded(tables, table.toString(), MAX_OBJECTS);
                }
                Collection<TableStat.Column> statementColumns = visitor.getColumns();
                for (TableStat.Column column : statementColumns) {
                    addBounded(columns, column.toString(), MAX_ANALYSIS_COLUMNS);
                }
            } catch (RuntimeException ignored) {
                // Some vendor-specific statements are syntactically valid but do not support schema statistics.
            }
        }
        return new SqlValidationResult(
                true,
                statements.size(),
                statementType,
                List.copyOf(tables),
                List.copyOf(columns),
                List.of()
        );
    }

    private static SqlValidationResult unparseable(String error) {
        return new SqlValidationResult(
                false,
                0,
                "UNKNOWN",
                List.of(),
                List.of(),
                List.of(error)
        );
    }

    private static String statementType(SQLStatement statement) {
        String name = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        if (name.startsWith("SQL")) {
            name = name.substring(3);
        }
        if (name.endsWith("STATEMENT")) {
            name = name.substring(0, name.length() - "STATEMENT".length());
        }
        return name;
    }

    private static String safeError(RuntimeException error) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
        return message.length() > 4096 ? message.substring(0, 4096) : message;
    }

    private static void addBounded(Set<String> values, String value, int max) {
        if (values.size() < max && StringUtils.isNotBlank(value)) {
            values.add(value);
        }
    }
}
