
package org.jumpserver.chen.framework.utils;

import com.alibaba.druid.DbType;
import com.alibaba.druid.FastsqlException;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.db2.ast.stmt.DB2SelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.odps.ast.OdpsSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.sqlserver.ast.SQLServerSelectQueryBlock;
import com.alibaba.druid.sql.dialect.sqlserver.ast.SQLServerTop;
import com.alibaba.druid.util.JdbcUtils;

import java.util.Iterator;
import java.util.List;

public class PageUtils {
    public PageUtils() {
    }

    public static String count(String sql, DbType dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        if (stmtList.size() != 1) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        } else {
            SQLStatement stmt = (SQLStatement) stmtList.get(0);
            if (!(stmt instanceof SQLSelectStatement)) {
                throw new IllegalArgumentException("sql not support count : " + sql);
            } else {
                SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
                return count(selectStmt.getSelect(), dbType);
            }
        }
    }

    public static String limit(String sql, DbType dbType, int offset, int count) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        if (stmtList.size() != 1) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        } else {
            SQLStatement stmt = (SQLStatement) stmtList.get(0);
            if (!(stmt instanceof SQLSelectStatement)) {
                throw new IllegalArgumentException("sql not support count : " + sql);
            } else {
                SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
                return limit(selectStmt.getSelect(), dbType, offset, count);
            }
        }
    }

    public static String limit(String sql, DbType dbType, int offset, int count, boolean check) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        if (stmtList.size() != 1) {
            throw new IllegalArgumentException("sql not support count : " + sql);
        } else {
            SQLStatement stmt = (SQLStatement) stmtList.get(0);
            if (!(stmt instanceof SQLSelectStatement)) {
                throw new IllegalArgumentException("sql not support count : " + sql);
            } else {
                SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
                limit(selectStmt.getSelect(), dbType, offset, count, check);
                return selectStmt.toString();
            }
        }
    }

    public static String limit(SQLSelect select, DbType dbType, int offset, int count) {
        limit(select, dbType, offset, count, false);
        return SQLUtils.toSQLString(select, dbType);
    }

    public static boolean limit(SQLSelect select, DbType dbType, int offset, int count, boolean check) {
        SQLSelectQuery query = select.getQuery();
        switch (dbType) {
            case oracle:
                return limitOracle(select, dbType, offset, count, check);
            case db2:
                return limitDB2(select, dbType, offset, count, check);
            case sqlserver:
            case jtds:
                return limitSQLServer(select, dbType, offset, count, check);
            default:
                if (query instanceof SQLSelectQueryBlock) {
                    return limitQueryBlock(select, dbType, offset, count, check);
                } else if (query instanceof SQLUnionQuery) {
                    return limitUnion((SQLUnionQuery) query, dbType, offset, count, check);
                } else {
                    throw new UnsupportedOperationException();
                }
        }
    }

    private static boolean limitUnion(SQLUnionQuery queryBlock, DbType dbType, int offset, int count, boolean check) {
        SQLLimit limit = queryBlock.getLimit();
        if (limit != null) {
            if (offset > 0) {
                limit.setOffset(new SQLIntegerExpr(offset));
            }

            if (check && limit.getRowCount() instanceof SQLNumericLiteralExpr) {
                int rowCount = ((SQLNumericLiteralExpr) limit.getRowCount()).getNumber().intValue();
                if (rowCount <= count && offset <= 0) {
                    return false;
                }
            } else if (check && limit.getRowCount() instanceof SQLVariantRefExpr) {
                return false;
            }

            limit.setRowCount(new SQLIntegerExpr(count));
        }

        if (limit == null) {
            limit = new SQLLimit();
            if (offset > 0) {
                limit.setOffset(new SQLIntegerExpr(offset));
            }

            limit.setRowCount(new SQLIntegerExpr(count));
            queryBlock.setLimit(limit);
        }

        return true;
    }

    private static boolean limitQueryBlock(SQLSelect select, DbType dbType, int offset, int count, boolean check) {
        SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) select.getQuery();
        if (dbType == null) {
            dbType = DbType.other;
        }

        switch (dbType) {
            case oracle:
            case oceanbase_oracle:
                return limitOracle(select, dbType, offset, count, check);
            case db2:
            case sqlserver:
            case jtds:
            default:
                throw new UnsupportedOperationException();
            case mysql:
            case mariadb:
            case tidb:
            case h2:
            case dm:
            case ads:
            case clickhouse:
                return limitMySqlQueryBlock(queryBlock, dbType, offset, count, check);
            case postgresql:
            case hive:
            case odps:
            case presto:
                return limitSQLQueryBlock(queryBlock, dbType, offset, count, check);
        }
    }

    private static boolean limitSQLQueryBlock(SQLSelectQueryBlock queryBlock, DbType dbType, int offset, int count, boolean check) {
        SQLLimit limit = queryBlock.getLimit();
        if (limit != null) {
            if (offset > 0) {
                limit.setOffset(new SQLIntegerExpr(offset));
            }

            if (check && limit.getRowCount() instanceof SQLNumericLiteralExpr) {
                int rowCount = ((SQLNumericLiteralExpr) limit.getRowCount()).getNumber().intValue();
                if (rowCount <= count && offset <= 0) {
                    return false;
                }
            }

            limit.setRowCount(new SQLIntegerExpr(count));
        }

        limit = new SQLLimit();
        if (offset > 0) {
            limit.setOffset(new SQLIntegerExpr(offset));
        }

        limit.setRowCount(new SQLIntegerExpr(count));
        queryBlock.setLimit(limit);
        return true;
    }

    private static boolean limitDB2(SQLSelect select, DbType dbType, int offset, int count, boolean check) {
        SQLSelectQuery query = select.getQuery();
        SQLBinaryOpExpr gt = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.GreaterThan, new SQLNumberExpr(offset), DbType.db2);
        SQLBinaryOpExpr lteq = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.LessThanOrEqual, new SQLNumberExpr(count + offset), DbType.db2);
        SQLBinaryOpExpr pageCondition = new SQLBinaryOpExpr(gt, SQLBinaryOperator.BooleanAnd, lteq, DbType.db2);
        DB2SelectQueryBlock queryBlock;
        SQLAggregateExpr aggregateExpr;
        SQLOrderBy orderBy;
        DB2SelectQueryBlock countQueryBlock;
        if (query instanceof SQLSelectQueryBlock) {
            queryBlock = (DB2SelectQueryBlock) query;
            if (offset <= 0) {
                SQLExpr first = queryBlock.getFirst();
                if (check && first != null && first instanceof SQLNumericLiteralExpr) {
                    int rowCount = ((SQLNumericLiteralExpr) first).getNumber().intValue();
                    if (rowCount < count) {
                        return false;
                    }
                }

                queryBlock.setFirst(new SQLIntegerExpr(count));
                return true;
            } else {
                aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
                orderBy = select.getOrderBy();
                if (orderBy == null && select.getQuery() instanceof SQLSelectQueryBlock) {
                    SQLSelectQueryBlock selectQueryBlcok = (SQLSelectQueryBlock) select.getQuery();
                    orderBy = selectQueryBlcok.getOrderBy();
                    selectQueryBlcok.setOrderBy((SQLOrderBy) null);
                } else {
                    select.setOrderBy((SQLOrderBy) null);
                }

                aggregateExpr.setOver(new SQLOver(orderBy));
                queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));
                countQueryBlock = new DB2SelectQueryBlock();
                countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
                countQueryBlock.setFrom(new SQLSubqueryTableSource(select.clone(), "XX"));
                countQueryBlock.setWhere(pageCondition);
                select.setQuery(countQueryBlock);
                return true;
            }
        } else {
            queryBlock = new DB2SelectQueryBlock();
            queryBlock.getSelectList().add(new SQLSelectItem(new SQLPropertyExpr(new SQLIdentifierExpr("XX"), "*")));
            aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
            orderBy = select.getOrderBy();
            aggregateExpr.setOver(new SQLOver(orderBy));
            select.setOrderBy((SQLOrderBy) null);
            queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));
            queryBlock.setFrom(new SQLSubqueryTableSource(select.clone(), "XX"));
            if (offset <= 0) {
                select.setQuery(queryBlock);
                return true;
            } else {
                countQueryBlock = new DB2SelectQueryBlock();
                countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
                countQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(queryBlock), "XXX"));
                countQueryBlock.setWhere(pageCondition);
                select.setQuery(countQueryBlock);
                return true;
            }
        }
    }

    private static boolean limitSQLServer(SQLSelect select, DbType dbType, int offset, int count, boolean check) {
        SQLSelectQuery query = select.getQuery();
        SQLBinaryOpExpr gt = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.GreaterThan, new SQLNumberExpr(offset), DbType.sqlserver);
        SQLBinaryOpExpr lteq = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.LessThanOrEqual, new SQLNumberExpr(count + offset), DbType.sqlserver);
        SQLBinaryOpExpr pageCondition = new SQLBinaryOpExpr(gt, SQLBinaryOperator.BooleanAnd, lteq, DbType.sqlserver);
        SQLServerSelectQueryBlock queryBlock;
        SQLAggregateExpr aggregateExpr;
        SQLOrderBy orderBy;
        SQLServerSelectQueryBlock countQueryBlock;
        if (query instanceof SQLSelectQueryBlock) {
            queryBlock = (SQLServerSelectQueryBlock) query;
            if (offset <= 0) {
                SQLServerTop top = queryBlock.getTop();
                if (check && top != null && !top.isPercent() && top.getExpr() instanceof SQLNumericLiteralExpr) {
                    int rowCount = ((SQLNumericLiteralExpr) top.getExpr()).getNumber().intValue();
                    if (rowCount <= count) {
                        return false;
                    }
                }
                queryBlock.setTop(new SQLServerTop(new SQLNumberExpr(count)));
                return true;
            } else {
                // 创建 SELECT NULL 的子查询
                SQLSelectQueryBlock selectQueryBlock = new SQLSelectQueryBlock();
                selectQueryBlock.addSelectItem(new SQLSelectItem(new SQLNullExpr()));

                SQLSelect selectNull = new SQLSelect();
                selectNull.setQuery(selectQueryBlock);

                SQLQueryExpr selectNullExpr = new SQLQueryExpr(selectNull);

                // 使用 SELECT NULL 的子查询作为 ORDER BY 的一部分
                SQLSelectOrderByItem orderByItem = new SQLSelectOrderByItem(selectNullExpr);
                SQLOrderBy orderByNull = new SQLOrderBy();
                orderByNull.addItem(orderByItem);

                aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
                aggregateExpr.setOver(new SQLOver(orderByNull));

                queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

                countQueryBlock = new SQLServerSelectQueryBlock();
                countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
                countQueryBlock.setFrom(new SQLSubqueryTableSource(select.clone(), "XX"));
                countQueryBlock.setWhere(pageCondition);
                select.setQuery(countQueryBlock);
                return true;
            }
        } else {
            queryBlock = new SQLServerSelectQueryBlock();
            if (offset <= 0) {
                queryBlock.setTop(new SQLServerTop(new SQLNumberExpr(count)));
                select.setQuery(queryBlock);
                return true;
            } else {
                // 重复上述逻辑，因为需要处理非 SQLSelectQueryBlock 的情况
                SQLSelectQueryBlock selectQueryBlockForNonBlock = new SQLSelectQueryBlock();
                selectQueryBlockForNonBlock.addSelectItem(new SQLSelectItem(new SQLNullExpr()));

                SQLSelect selectNullForNonBlock = new SQLSelect();
                selectNullForNonBlock.setQuery(selectQueryBlockForNonBlock);

                SQLQueryExpr selectNullExprForNonBlock = new SQLQueryExpr(selectNullForNonBlock);

                SQLSelectOrderByItem orderByItemForNonBlock = new SQLSelectOrderByItem(selectNullExprForNonBlock);
                SQLOrderBy orderByNullForNonBlock = new SQLOrderBy();
                orderByNullForNonBlock.addItem(orderByItemForNonBlock);

                aggregateExpr = new SQLAggregateExpr("ROW_NUMBER");
                aggregateExpr.setOver(new SQLOver(orderByNullForNonBlock));

                queryBlock.getSelectList().add(new SQLSelectItem(aggregateExpr, "ROWNUM"));

                countQueryBlock = new SQLServerSelectQueryBlock();
                countQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
                countQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(queryBlock), "XXX"));
                countQueryBlock.setWhere(pageCondition);
                select.setQuery(countQueryBlock);
                return true;
            }
        }
    }


    private static boolean limitOracle(SQLSelect select, DbType dbType, int offset, int count, boolean check) {
        SQLSelectQuery query = select.getQuery();
        OracleSelectQueryBlock queryBlock;
        if (query instanceof SQLSelectQueryBlock) {
            queryBlock = (OracleSelectQueryBlock) query;
            SQLOrderBy orderBy = select.getOrderBy();
            if (orderBy == null && queryBlock.getOrderBy() != null) {
                orderBy = queryBlock.getOrderBy();
            }

//            if (queryBlock.getGroupBy() == null && orderBy == null && offset <= 0) {
//                SQLExpr where = queryBlock.getWhere();
//                SQLBinaryOpExpr condition;
//                if (check && where instanceof SQLBinaryOpExpr) {
//                    condition = (SQLBinaryOpExpr) where;
//                    if (condition.getOperator() == SQLBinaryOperator.LessThanOrEqual) {
//                        SQLExpr left = condition.getLeft();
//                        SQLExpr right = condition.getRight();
//                        if (left instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) left).getName().equalsIgnoreCase("ROWNUM") && right instanceof SQLNumericLiteralExpr) {
//                            int rowCount = ((SQLNumericLiteralExpr) right).getNumber().intValue();
//                            if (rowCount <= count) {
//                                return false;
//                            }
//                        }
//                    }
//                }
//
//                condition = new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.LessThanOrEqual, new SQLNumberExpr(count), DbType.oracle);
//
//
//                if (queryBlock.getWhere() == null) {
//                    queryBlock.setWhere(condition);
//                } else {
//                    queryBlock.setWhere(new SQLBinaryOpExpr(queryBlock.getWhere(), SQLBinaryOperator.BooleanAnd, condition, DbType.oracle));
//                }
//
//                return true;
//            }
        }

        queryBlock = new OracleSelectQueryBlock();
        queryBlock.getSelectList().add(new SQLSelectItem(new SQLPropertyExpr(new SQLIdentifierExpr("XX"), "*")));
        queryBlock.setFrom(new SQLSubqueryTableSource(select.clone(), "XX"));
        queryBlock.setWhere(new SQLBinaryOpExpr(new SQLIdentifierExpr("ROWNUM"), SQLBinaryOperator.LessThanOrEqual, new SQLNumberExpr(count + offset), DbType.oracle));
        select.setOrderBy((SQLOrderBy) null);
        if (offset <= 0) {
            select.setQuery(queryBlock);
            return true;
        } else {
            queryBlock.getSelectList().add(new SQLSelectItem(new SQLIdentifierExpr("ROWNUM"), "RN"));

            OracleSelectQueryBlock offsetQueryBlock = new OracleSelectQueryBlock();
            offsetQueryBlock.getSelectList().add(new SQLSelectItem(new SQLAllColumnExpr()));
            offsetQueryBlock.setFrom(new SQLSubqueryTableSource(new SQLSelect(queryBlock), "XXX"));
            offsetQueryBlock.setWhere(new SQLBinaryOpExpr(new SQLIdentifierExpr("RN"), SQLBinaryOperator.GreaterThan, new SQLNumberExpr(offset), DbType.oracle));
            select.setQuery(offsetQueryBlock);
            return true;
        }
    }

    private static boolean limitMySqlQueryBlock(SQLSelectQueryBlock queryBlock, DbType dbType, int offset, int count, boolean check) {
        SQLLimit limit = queryBlock.getLimit();
        if (limit != null) {
            if (offset > 0) {
                limit.setOffset(new SQLIntegerExpr(offset));
            }

            if (check && limit.getRowCount() instanceof SQLNumericLiteralExpr) {
                int rowCount = ((SQLNumericLiteralExpr) limit.getRowCount()).getNumber().intValue();
                if (rowCount <= count && offset <= 0) {
                    return false;
                }
            } else if (check && limit.getRowCount() instanceof SQLVariantRefExpr) {
                return false;
            }

            limit.setRowCount(new SQLIntegerExpr(count));
        }

        if (limit == null) {
            limit = new SQLLimit();
            if (offset > 0) {
                limit.setOffset(new SQLIntegerExpr(offset));
            }

            limit.setRowCount(new SQLIntegerExpr(count));
            queryBlock.setLimit(limit);
        }

        return true;
    }

    private static String count(SQLSelect select, DbType dbType) {
        // 去除 ORDER BY
        if (select.getOrderBy() != null) {
            select.setOrderBy(null);
        }

        SQLSelectQuery query = select.getQuery();
        clearOrderBy(query); // 自定义的清理嵌套子查询中的 ORDER BY 的方法

        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;
            List<SQLSelectItem> selectList = queryBlock.getSelectList();
            int distinctOption = queryBlock.getDistionOption();

            // 情况 1: 存在 GROUP BY，需用子查询
            if (queryBlock.getGroupBy() != null && queryBlock.getGroupBy().getItems().size() > 0) {
                if (selectList.size() == 1 && selectList.get(0).getExpr() instanceof SQLAllColumnExpr) {
                    selectList.clear();
                    selectList.add(new SQLSelectItem(new SQLIntegerExpr(1)));
                }
                return createCountUseSubQuery(select, dbType);
            }

            // 情况 2: DISTINCT 情况下，Oracle 特别处理
            if (distinctOption == SQLSetQuantifier.DISTINCT) {
                if (dbType == DbType.oracle && (
                        selectList.size() > 1 ||
                                (selectList.size() == 1 && selectList.get(0).getExpr() instanceof SQLAllColumnExpr)
                )) {
                    // SELECT DISTINCT * 或多字段，Oracle 不支持直接 count(distinct ...)
                    return createCountUseSubQuery(select, dbType);
                } else {
                    // 只有一个字段，Oracle 支持 count(distinct col)
                    SQLAggregateExpr countExpr = new SQLAggregateExpr("COUNT", SQLAggregateOption.DISTINCT);
                    for (SQLSelectItem item : selectList) {
                        countExpr.addArgument(item.getExpr());
                    }
                    selectList.clear();
                    queryBlock.setDistionOption(0); // 去除 DISTINCT
                    queryBlock.addSelectItem(countExpr);
                    return SQLUtils.toSQLString(select, dbType);
                }
            }

            // 情况 3: 非 DISTINCT，无 GROUP BY，直接 count(*)
            selectList.clear();
            selectList.add(createCountItem(dbType));
            return SQLUtils.toSQLString(select, dbType);

        } else if (query instanceof SQLUnionQuery) {
            // UNION 情况统一使用子查询
            return createCountUseSubQuery(select, dbType);
        } else {
            throw new IllegalStateException("不支持的 SQL 查询类型: " + query.getClass().getName());
        }
    }

    private static String createCountUseSubQuery(SQLSelect select, DbType dbType) {
        SQLSelectQueryBlock countSelectQuery = createQueryBlock(dbType);
        SQLSelectItem countItem = createCountItem(dbType);
        countSelectQuery.getSelectList().add(countItem);
        SQLSubqueryTableSource fromSubquery = new SQLSubqueryTableSource(select);
        fromSubquery.setAlias("ALIAS_COUNT");
        countSelectQuery.setFrom(fromSubquery);
        SQLSelect countSelect = new SQLSelect(countSelectQuery);
        SQLSelectStatement countStmt = new SQLSelectStatement(countSelect, dbType);
        return SQLUtils.toSQLString(countStmt, dbType);
    }

    private static SQLSelectQueryBlock createQueryBlock(DbType dbType) {
        if (dbType == null) {
            dbType = DbType.other;
        }

        switch (dbType) {
            case oracle:
                return new OracleSelectQueryBlock();
            case db2:
                return new DB2SelectQueryBlock();
            case sqlserver:
            case jtds:
                return new SQLServerSelectQueryBlock();
            case mysql:
            case mariadb:
            case tidb:
            case ads:
                return new MySqlSelectQueryBlock();
            case h2:
            case clickhouse:
            case hive:
            default:
                return new SQLSelectQueryBlock(dbType);
            case postgresql:
                return new PGSelectQueryBlock();
            case odps:
                return new OdpsSelectQueryBlock();
        }
    }

    private static SQLSelectItem createCountItem(DbType dbType) {
        SQLAggregateExpr countExpr = new SQLAggregateExpr("COUNT");
        countExpr.addArgument(new SQLAllColumnExpr());
        SQLSelectItem countItem = new SQLSelectItem(countExpr);
        return countItem;
    }

    private static void clearOrderBy(SQLSelectQuery query) {
        if (query instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;
            if (queryBlock.getOrderBy() != null) {
                queryBlock.setOrderBy((SQLOrderBy) null);
            }

        } else {
            if (query instanceof SQLUnionQuery) {
                SQLUnionQuery union = (SQLUnionQuery) query;
                if (union.getOrderBy() != null) {
                    union.setOrderBy((SQLOrderBy) null);
                }

                clearOrderBy(union.getLeft());
                clearOrderBy(union.getRight());
            }

        }
    }

    public static int getLimit(String sql, DbType dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        if (stmtList.size() != 1) {
            return -1;
        } else {
            SQLStatement stmt = (SQLStatement) stmtList.get(0);
            if (stmt instanceof SQLSelectStatement) {
                SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
                SQLSelectQuery query = selectStmt.getSelect().getQuery();
                if (query instanceof SQLSelectQueryBlock) {
                    SQLLimit limit;
                    SQLExpr rowCountExpr;
                    int rowCount;
                    if (query instanceof MySqlSelectQueryBlock) {
                        limit = ((MySqlSelectQueryBlock) query).getLimit();
                        if (limit == null) {
                            return -1;
                        }

                        rowCountExpr = limit.getRowCount();
                        if (rowCountExpr instanceof SQLNumericLiteralExpr) {
                            rowCount = ((SQLNumericLiteralExpr) rowCountExpr).getNumber().intValue();
                            return rowCount;
                        }

                        return Integer.MAX_VALUE;
                    }
                    if (query instanceof PGSelectQueryBlock) {
                        limit = ((PGSelectQueryBlock) query).getLimit();
                        if (limit == null) {
                            return -1;
                        }

                        rowCountExpr = limit.getRowCount();
                        if (rowCountExpr instanceof SQLNumericLiteralExpr) {
                            rowCount = ((SQLNumericLiteralExpr) rowCountExpr).getNumber().intValue();
                            return rowCount;
                        }
                        return Integer.MAX_VALUE;
                    }

                    if (query instanceof OdpsSelectQueryBlock) {
                        limit = ((OdpsSelectQueryBlock) query).getLimit();
                        rowCountExpr = limit != null ? limit.getRowCount() : null;
                        if (rowCountExpr instanceof SQLNumericLiteralExpr) {
                            rowCount = ((SQLNumericLiteralExpr) rowCountExpr).getNumber().intValue();
                            return rowCount;
                        }

                        return Integer.MAX_VALUE;
                    }

                    return -1;
                }
            }

            return -1;
        }
    }

    public static boolean hasUnorderedLimit(String sql, DbType dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
        Iterator var4;
        SQLStatement stmt;
        if (JdbcUtils.isMysqlDbType(dbType)) {
            MySqlUnorderedLimitDetectVisitor visitor = new MySqlUnorderedLimitDetectVisitor();
            var4 = stmtList.iterator();

            while (var4.hasNext()) {
                stmt = (SQLStatement) var4.next();
                stmt.accept(visitor);
            }

            return visitor.unorderedLimitCount > 0;
        } else if (DbType.oracle != dbType) {
            throw new FastsqlException("not supported. dbType : " + dbType);
        } else {
            OracleUnorderedLimitDetectVisitor visitor = new OracleUnorderedLimitDetectVisitor();
            var4 = stmtList.iterator();

            while (var4.hasNext()) {
                stmt = (SQLStatement) var4.next();
                stmt.accept(visitor);
            }

            return visitor.unorderedLimitCount > 0;
        }
    }

    private static class MySqlUnorderedLimitDetectVisitor extends MySqlASTVisitorAdapter {
        public int unorderedLimitCount;

        private MySqlUnorderedLimitDetectVisitor() {
        }

        public boolean visit(MySqlSelectQueryBlock x) {
            SQLOrderBy orderBy = x.getOrderBy();
            SQLLimit limit = x.getLimit();
            if (limit != null && (orderBy == null || orderBy.getItems().isEmpty())) {
                boolean subQueryHasOrderBy = false;
                SQLTableSource from = x.getFrom();
                if (from instanceof SQLSubqueryTableSource) {
                    SQLSubqueryTableSource subqueryTabSrc = (SQLSubqueryTableSource) from;
                    SQLSelect select = subqueryTabSrc.getSelect();
                    if (select.getQuery() instanceof SQLSelectQueryBlock) {
                        SQLSelectQueryBlock subquery = (SQLSelectQueryBlock) select.getQuery();
                        if (subquery.getOrderBy() != null && subquery.getOrderBy().getItems().size() > 0) {
                            subQueryHasOrderBy = true;
                        }
                    }
                }

                if (!subQueryHasOrderBy) {
                    ++this.unorderedLimitCount;
                }
            }

            return true;
        }
    }

    private static class OracleUnorderedLimitDetectVisitor extends OracleASTVisitorAdapter {
        public int unorderedLimitCount;

        private OracleUnorderedLimitDetectVisitor() {
        }

        public boolean visit(SQLBinaryOpExpr x) {
            SQLExpr left = x.getLeft();
            SQLExpr right = x.getRight();
            boolean rownum = false;
            if (left instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) left).getName().equalsIgnoreCase("ROWNUM") && right instanceof SQLLiteralExpr) {
                rownum = true;
            } else if (right instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) right).getName().equalsIgnoreCase("ROWNUM") && left instanceof SQLLiteralExpr) {
                rownum = true;
            }

            OracleSelectQueryBlock selectQuery = null;
            if (rownum) {
                for (SQLObject parent = x.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof SQLSelectQuery) {
                        if (parent instanceof OracleSelectQueryBlock) {
                            OracleSelectQueryBlock queryBlock = (OracleSelectQueryBlock) parent;
                            SQLTableSource from = queryBlock.getFrom();
                            if (from instanceof SQLExprTableSource) {
                                selectQuery = queryBlock;
                            } else if (from instanceof SQLSubqueryTableSource) {
                                SQLSelect subSelect = ((SQLSubqueryTableSource) from).getSelect();
                                if (subSelect.getQuery() instanceof OracleSelectQueryBlock) {
                                    selectQuery = (OracleSelectQueryBlock) subSelect.getQuery();
                                }
                            }
                        }
                        break;
                    }
                }
            }

            if (selectQuery != null) {
                SQLOrderBy orderBy = selectQuery.getOrderBy();
                SQLObject parent = selectQuery.getParent();
                if (orderBy == null && parent instanceof SQLSelect) {
                    SQLSelect select = (SQLSelect) parent;
                    orderBy = select.getOrderBy();
                }

                if (orderBy == null || orderBy.getItems().isEmpty()) {
                    ++this.unorderedLimitCount;
                }
            }

            return true;
        }

        public boolean visit(OracleSelectQueryBlock queryBlock) {
            boolean isExprTableSrc = queryBlock.getFrom() instanceof SQLExprTableSource;
            if (!isExprTableSrc) {
                return true;
            } else {
                boolean rownum = false;
                Iterator var4 = queryBlock.getSelectList().iterator();

                while (var4.hasNext()) {
                    SQLSelectItem item = (SQLSelectItem) var4.next();
                    SQLExpr itemExpr = item.getExpr();
                    if (itemExpr instanceof SQLIdentifierExpr && ((SQLIdentifierExpr) itemExpr).getName().equalsIgnoreCase("ROWNUM")) {
                        rownum = true;
                        break;
                    }
                }

                if (!rownum) {
                    return true;
                } else {
                    SQLObject parent = queryBlock.getParent();
                    if (!(parent instanceof SQLSelect)) {
                        return true;
                    } else {
                        SQLSelect select = (SQLSelect) parent;
                        if (select.getOrderBy() == null || select.getOrderBy().getItems().isEmpty()) {
                            ++this.unorderedLimitCount;
                        }

                        return false;
                    }
                }
            }
        }
    }
}
