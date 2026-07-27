package org.jumpserver.chen.framework.datasource.edit;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.console.entity.response.SaveChangesResult;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.edit.bind.PreparedStatementBinder;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.OptimisticLockConflictException;
import org.jumpserver.chen.framework.datasource.edit.exception.RowNotFoundOrNotUniqueException;
import org.jumpserver.chen.framework.datasource.edit.exception.UnexpectedAffectedRowsException;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.wisp.Common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Slf4j
public class TableChangesSaveService {
    public static final String ACL_REJECTED = "ACL_REJECTED";
    public static final String OPTIMISTIC_LOCK_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";
    public static final String ROW_NOT_FOUND_OR_NOT_UNIQUE = "ROW_NOT_FOUND_OR_NOT_UNIQUE";
    public static final String AFFECTED_ROWS_UNEXPECTED = "AFFECTED_ROWS_UNEXPECTED";
    public static final String SAVE_CHANGES_EXECUTE_FAILED = "SAVE_CHANGES_EXECUTE_FAILED";
    public static final String SAVE_CHANGES_COMMIT_FAILED = "SAVE_CHANGES_COMMIT_FAILED";
    public static final String SAVE_CHANGES_AUDIT_REJECTED = "SAVE_CHANGES_AUDIT_REJECTED";

    private final TableChangesPlanBuilder planBuilder;
    private final PreparedStatementBinder binder;

    public TableChangesSaveService() {
        this(new TableChangesPlanBuilder(), new PreparedStatementBinder());
    }

    TableChangesSaveService(TableChangesPlanBuilder planBuilder, PreparedStatementBinder binder) {
        this.planBuilder = planBuilder;
        this.binder = binder;
    }

    public SaveChangesResult save(
            TableEditContext context,
            String actionDataView,
            SaveChangesRequest request,
            ConnectionManager connectionManager,
            Session session
    ) {
        SaveChangesResult result = baseResult(context);
        TableChangesPlanBuildResult buildResult = this.planBuilder.build(context, actionDataView, request);
        if (!buildResult.isSuccess()) {
            log.warn(
                    "save changes plan rejected, reason={}, dataView={}, actionDataView={}, table={}.{}, failedChangeIndex={}",
                    buildResult.getReason(),
                    context.getDataViewTitle(),
                    actionDataView,
                    context.getSchema(),
                    context.getTable(),
                    buildResult.getFailedChangeIndex()
            );
            return reject(result, buildResult.getReason(), buildResult.getFailedChangeIndex(),
                    buildResult.getFailedChange(), buildResult.getFailedOperation());
        }

        TableChangesPlan plan = buildResult.getPlan();
        fillPlanResult(result, plan);

        log.info("save_changes auditSql:\n{}", plan.getAuditSql());

        try (Connection connection = connectionManager.getConnection()) {
            log.debug(
                    "save changes plan accepted, dataView={}, table={}.{}, changeCount={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    plan.getChangeCount()
            );

            ACLResult aclResult = session.checkACL(plan.getAclSql(), connection);
            if (isRejected(aclResult)) {
                log.warn(
                        "save changes acl rejected, dataView={}, table={}.{}, riskLevel={}",
                        plan.getDataView(),
                        plan.getSchema(),
                        plan.getTable(),
                        aclResult.getRiskLevel()
                );
                return reject(result, ACL_REJECTED, null, null);
            }
            if (aclResult == null) {
                aclResult = new ACLResult();
                aclResult.setRiskLevel(Common.RiskLevel.Normal);
            }

            ACLResult finalAclResult = aclResult;
            SQLQueryResult queryResult = session.withAudit(
                    plan.getAuditSql(),
                    () -> executeTransaction(connection, plan, finalAclResult)
            );
            result.setSuccess(true);
            result.setAllowed(true);
            result.setChangeCount(queryResult.getUpdateCount());
            // executeTransaction has already verified every command affected exactly one row.
            result.getStatements().forEach(item -> item.setAffectedRows(1));

            log.info(
                    "save changes succeeded, dataView={}, table={}.{}, changeCount={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    plan.getChangeCount()
            );
            return result;
        } catch (OptimisticLockConflictException e) {
            PreparedTableChangeCommand command = commandAt(plan, e.getChangeIndex());
            log.warn(
                    "save changes optimistic lock conflict, dataView={}, table={}.{}, changeIndex={}, sourceColumn={}, pkColumn={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getChangeIndex(),
                    command != null ? command.getSourceColumn() : null,
                    command != null ? command.getPkColumn() : null
            );
            return reject(result, OPTIMISTIC_LOCK_CONFLICT, e.getChangeIndex(),
                    command != null ? command.getChange() : null, command);
        } catch (UnexpectedAffectedRowsException e) {
            PreparedTableChangeCommand command = commandAt(plan, e.getChangeIndex());
            log.error(
                    "save changes affected rows unexpected, dataView={}, table={}.{}, changeIndex={}, sourceColumn={}, pkColumn={}, affectedRows={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getChangeIndex(),
                    command != null ? command.getSourceColumn() : null,
                    command != null ? command.getPkColumn() : null,
                    e.getAffectedRows()
            );
            return reject(result, AFFECTED_ROWS_UNEXPECTED, e.getChangeIndex(),
                    command != null ? command.getChange() : null, command);
        } catch (RowNotFoundOrNotUniqueException e) {
            PreparedTableChangeCommand command = commandAt(plan, e.getChangeIndex());
            log.warn(
                    "save changes row not found or not unique, dataView={}, table={}.{}, changeIndex={}, operation={}, pkColumn={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getChangeIndex(),
                    command != null ? command.getOperation() : null,
                    command != null ? command.getPkColumn() : null
            );
            return reject(result, ROW_NOT_FOUND_OR_NOT_UNIQUE, e.getChangeIndex(),
                    command != null ? command.getChange() : null, command);
        } catch (CommitFailedException e) {
            log.error(
                    "save changes commit failed, dataView={}, table={}.{}, message={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getMessage(),
                    e
            );
            return reject(result, SAVE_CHANGES_COMMIT_FAILED, null, null);
        } catch (CommandRejectException e) {
            log.warn(
                    "save changes audit rejected, dataView={}, table={}.{}, message={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getMessage(),
                    e
            );
            return reject(result, SAVE_CHANGES_AUDIT_REJECTED, null, null);
        } catch (SQLException e) {
            log.warn(
                    "save changes failed, reason={}, dataView={}, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    SAVE_CHANGES_EXECUTE_FAILED,
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            return reject(result, SAVE_CHANGES_EXECUTE_FAILED, null, null);
        }
    }

    SQLQueryResult executeTransaction(Connection connection, TableChangesPlan plan, ACLResult aclResult) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            for (int i = 0; i < plan.getCommands().size(); i++) {
                executeCommand(connection, plan, plan.getCommands().get(i), i);
            }
            commitTransaction(connection, plan);
            return successQueryResult(plan, aclResult);
        } catch (SQLException e) {
            if (!isExpectedSaveException(e)) {
                log.warn(
                        "save changes transaction failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                        plan.getSchema(),
                        plan.getTable(),
                        e.getSQLState(),
                        e.getErrorCode(),
                        e.getMessage(),
                        e
                );
                rollbackQuietly(connection, plan);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                log.warn(
                        "restore save changes connection autoCommit failed, table={}.{}, originalAutoCommit={}, sqlState={}, vendorCode={}, message={}",
                        plan.getSchema(),
                        plan.getTable(),
                        originalAutoCommit,
                        e.getSQLState(),
                        e.getErrorCode(),
                        e.getMessage(),
                        e
                );
                throw e;
            }
        }
    }

    private void executeCommand(
            Connection connection,
            TableChangesPlan plan,
            PreparedTableChangeCommand command,
            int changeIndex
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(command.getPreparedSql())) {
            this.binder.bind(statement, command);

            int affectedRows;
            try {
                affectedRows = statement.executeUpdate();
            } catch (SQLException e) {
                log.warn(
                        "save changes statement execute failed, table={}.{}, changeIndex={}, operation={}, sourceColumn={}, pkColumn={}, sqlState={}, vendorCode={}, message={}",
                        plan.getSchema(),
                        plan.getTable(),
                        changeIndex,
                        command.getOperation(),
                        command.getSourceColumn(),
                        command.getPkColumn(),
                        e.getSQLState(),
                        e.getErrorCode(),
                        e.getMessage(),
                        e
                );
                throw e;
            }

            handleAffectedRows(connection, plan, command, changeIndex, affectedRows);
        }
    }

    private void handleAffectedRows(
            Connection connection,
            TableChangesPlan plan,
            PreparedTableChangeCommand command,
            int changeIndex,
            int affectedRows
    ) throws SQLException {
        if (affectedRows == 0) {
            log.warn(
                    "save changes affected zero rows, table={}.{}, changeIndex={}, operation={}, sourceColumn={}, pkColumn={}",
                    plan.getSchema(),
                    plan.getTable(),
                    changeIndex,
                    command.getOperation(),
                    command.getSourceColumn(),
                    command.getPkColumn()
            );
            rollbackQuietly(connection, plan);
            if (command.getOperation() == PreparedTableChangeCommand.Operation.DELETE) {
                throw new RowNotFoundOrNotUniqueException(changeIndex);
            }
            if (command.getOperation() == PreparedTableChangeCommand.Operation.INSERT) {
                throw new UnexpectedAffectedRowsException(changeIndex, affectedRows);
            }
            throw new OptimisticLockConflictException(changeIndex);
        }
        if (affectedRows > 1) {
            log.error(
                    "save changes affected unexpected rows, table={}.{}, changeIndex={}, operation={}, sourceColumn={}, pkColumn={}, affectedRows={}",
                    plan.getSchema(),
                    plan.getTable(),
                    changeIndex,
                    command.getOperation(),
                    command.getSourceColumn(),
                    command.getPkColumn(),
                    affectedRows
            );
            rollbackQuietly(connection, plan);
            throw new UnexpectedAffectedRowsException(changeIndex, affectedRows);
        }
    }

    private void commitTransaction(Connection connection, TableChangesPlan plan) throws SQLException {
        try {
            connection.commit();
        } catch (SQLException e) {
            log.error(
                    "save changes commit failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
            rollbackQuietly(connection, plan);
            throw new CommitFailedException(e);
        }
    }

    private SQLQueryResult successQueryResult(TableChangesPlan plan, ACLResult aclResult) {
        SQLQueryResult result = new SQLQueryResult(plan.getAuditSql());
        result.setHasResultSet(false);
        result.setUpdateCount(plan.getChangeCount());
        result.setAclResult(aclResult);
        return result;
    }

    private boolean isExpectedSaveException(SQLException e) {
        return e instanceof OptimisticLockConflictException ||
                e instanceof RowNotFoundOrNotUniqueException ||
                e instanceof UnexpectedAffectedRowsException ||
                e instanceof CommitFailedException;
    }

    private boolean isRejected(ACLResult aclResult) {
        return aclResult != null &&
                (aclResult.getRiskLevel() == Common.RiskLevel.Reject ||
                        aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject);
    }

    private void rollbackQuietly(Connection connection, TableChangesPlan plan) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn(
                    "rollback save changes transaction failed, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    plan != null ? plan.getSchema() : null,
                    plan != null ? plan.getTable() : null,
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage(),
                    e
            );
        }
    }

    private SaveChangesResult baseResult(TableEditContext context) {
        SaveChangesResult result = new SaveChangesResult();
        result.setSuccess(false);
        result.setAllowed(false);
        result.setDataView(context.getDataViewTitle());
        result.setSchema(context.getSchema());
        result.setTable(context.getTable());
        return result;
    }

    private void fillPlanResult(SaveChangesResult result, TableChangesPlan plan) {
        result.setDataView(plan.getDataView());
        result.setSchema(plan.getSchema());
        result.setTable(plan.getTable());
        result.setChangeCount(plan.getChangeCount());
        result.setUpdateCount(plan.getUpdateCount());
        result.setInsertCount(plan.getInsertCount());
        result.setDeleteCount(plan.getDeleteCount());
        result.setAuditSql(plan.getAuditSql());
        for (PreparedTableChangeCommand command : plan.getCommands()) {
            SaveChangesResult.ResultItem item = new SaveChangesResult.ResultItem();
            item.setOperation(command.getOperation().name());
            item.setSourceColumn(command.getSourceColumn());
            item.setPkColumn(command.getPkColumn());
            item.setPreparedSql(command.getPreparedSql());
            result.getStatements().add(item);
        }
    }

    private PreparedTableChangeCommand commandAt(TableChangesPlan plan, int index) {
        if (plan == null || plan.getCommands() == null || index < 0 || index >= plan.getCommands().size()) {
            return null;
        }
        return plan.getCommands().get(index);
    }

    private SaveChangesResult reject(
            SaveChangesResult result,
            String reason,
            Integer failedChangeIndex,
            SaveChangesRequest.ChangeItem failedChange
    ) {
        return reject(result, reason, failedChangeIndex, failedChange, failedChange);
    }

    private SaveChangesResult reject(
            SaveChangesResult result,
            String reason,
            Integer failedChangeIndex,
            SaveChangesRequest.ChangeItem failedChange,
            Object failedOperation
    ) {
        result.setSuccess(false);
        result.setAllowed(false);
        result.setReason(reason);
        result.setFailedChangeIndex(failedChangeIndex);
        result.setFailedChange(failedChange);
        result.setFailedOperation(failedOperation);
        return result;
    }
}
