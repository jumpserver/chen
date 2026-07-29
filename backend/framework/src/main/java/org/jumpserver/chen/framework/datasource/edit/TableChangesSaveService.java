package org.jumpserver.chen.framework.datasource.edit;

import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.entity.request.SaveChangesRequest;
import org.jumpserver.chen.framework.console.entity.response.SaveChangesResult;
import org.jumpserver.chen.framework.datasource.edit.bind.PreparedStatementBinder;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;
import org.jumpserver.chen.framework.datasource.edit.exception.CommitFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.OptimisticLockConflictException;
import org.jumpserver.chen.framework.datasource.edit.exception.RollbackFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.RowNotFoundOrNotUniqueException;
import org.jumpserver.chen.framework.datasource.edit.exception.SavepointRollbackFailedException;
import org.jumpserver.chen.framework.datasource.edit.exception.UnexpectedAffectedRowsException;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.acl.ACLCommandContext;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.wisp.Common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TableChangesSaveService {
    public static final String ACL_REJECTED = "ACL_REJECTED";
    public static final String ACL_RISK_LEVEL_UNRECOGNIZED = "ACL_RISK_LEVEL_UNRECOGNIZED";
    public static final String OPTIMISTIC_LOCK_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";
    public static final String ROW_NOT_FOUND_OR_NOT_UNIQUE = "ROW_NOT_FOUND_OR_NOT_UNIQUE";
    public static final String AFFECTED_ROWS_UNEXPECTED = "AFFECTED_ROWS_UNEXPECTED";
    public static final String SAVE_CHANGES_EXECUTE_FAILED = "SAVE_CHANGES_EXECUTE_FAILED";
    public static final String SAVE_CHANGES_COMMIT_OUTCOME_UNKNOWN = "SAVE_CHANGES_COMMIT_OUTCOME_UNKNOWN";
    public static final String SAVE_CHANGES_ROLLBACK_FAILED = "SAVE_CHANGES_ROLLBACK_FAILED";
    public static final String SAVE_CHANGES_SAVEPOINT_ROLLBACK_FAILED = "SAVE_CHANGES_SAVEPOINT_ROLLBACK_FAILED";
    public static final String SAVE_CHANGES_AUDIT_REJECTED = "SAVE_CHANGES_AUDIT_REJECTED";
    public static final String SAVE_CHANGES_AUDIT_FAILED_AFTER_COMMIT = "SAVE_CHANGES_AUDIT_FAILED_AFTER_COMMIT";
    public static final String SAVE_CHANGES_AUDIT_FAILED_AFTER_APPLY = "SAVE_CHANGES_AUDIT_FAILED_AFTER_APPLY";

    private final TableChangesPlanBuilder planBuilder;
    private final PreparedStatementBinder binder;
    private final ServiceManagedTransactionBoundary serviceManagedTransactionBoundary;

    public TableChangesSaveService() {
        this(new TableChangesPlanBuilder(), new PreparedStatementBinder());
    }

    TableChangesSaveService(TableChangesPlanBuilder planBuilder, PreparedStatementBinder binder) {
        this.planBuilder = planBuilder;
        this.binder = binder;
        this.serviceManagedTransactionBoundary = new ServiceManagedTransactionBoundary();
    }

    public SaveChangesResult save(
            TableEditContext context,
            String actionDataView,
            SaveChangesRequest request,
            SaveExecutionContext executionContext,
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

        try {
            TransactionBoundary transactionBoundary = transactionBoundary(executionContext, context.getDbType());
            if (executionContext.transactionMode() == TransactionMode.SERVICE_MANAGED) {
                try (executionContext) {
                    return executeAcceptedSave(result, plan, executionContext.connection(),
                            executionContext.connectionOwnership(), executionContext.transactionMode(),
                            session, transactionBoundary);
                }
            }
            return executeAcceptedSave(result, plan, executionContext.connection(),
                    executionContext.connectionOwnership(), executionContext.transactionMode(),
                    session, transactionBoundary);
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
        } catch (RollbackFailedException e) {
            log.error(
                    "save changes rollback failed and connection was invalidated, dataView={}, table={}.{}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e
            );
            return reject(result, SAVE_CHANGES_ROLLBACK_FAILED, null, null);
        } catch (SavepointRollbackFailedException e) {
            log.error(
                    "save changes rollback to savepoint failed and connection was invalidated, dataView={}, table={}.{}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e
            );
            return reject(result, SAVE_CHANGES_SAVEPOINT_ROLLBACK_FAILED, null, null);
        } catch (CommitFailedException e) {
            // A commit failure is an unknown outcome: the database may have committed despite
            // the client error. The connection has already been discarded by the boundary; the
            // caller must not let the user treat this as a retriable failure.
            log.error(
                    "save changes commit outcome unknown, dataView={}, table={}.{}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable()
            );
            return reject(result, SAVE_CHANGES_COMMIT_OUTCOME_UNKNOWN, null, null);
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
            // Statement-level detail (changeIndex, columns, sqlState) was already logged by
            // executeCommand; record only the save-level summary here.
            log.warn(
                    "save changes failed, reason={}, dataView={}, table={}.{}, sqlState={}, vendorCode={}, message={}",
                    SAVE_CHANGES_EXECUTE_FAILED,
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e.getSQLState(),
                    e.getErrorCode(),
                    e.getMessage()
            );
            return reject(result, SAVE_CHANGES_EXECUTE_FAILED, null, null);
        }
    }

    private SaveChangesResult executeAcceptedSave(
            SaveChangesResult result,
            TableChangesPlan plan,
            Connection connection,
            ConnectionOwnership connectionOwnership,
            TransactionMode transactionMode,
            Session session,
            TransactionBoundary transactionBoundary
    ) throws SQLException, CommandRejectException {
        log.debug(
                "save changes plan accepted, dataView={}, table={}.{}, changeCount={}",
                plan.getDataView(),
                plan.getSchema(),
                plan.getTable(),
                plan.getChangeCount()
        );

        ACLResult aclResult = checkBatchACL(plan, session, connection, connectionOwnership);
        if (aclResult.getRiskLevel() == Common.RiskLevel.UNRECOGNIZED) {
            log.error(
                    "save changes acl returned unrecognized risk level, dataView={}, table={}.{}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable()
            );
            return reject(result, ACL_RISK_LEVEL_UNRECOGNIZED, null, null);
        }
        if (isRejected(aclResult)) {
            log.warn(
                    "save changes acl rejected, dataView={}, table={}.{}, riskLevel={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    aclResult.getRiskLevel()
            );
            recordRejectedCommand(session, plan, aclResult);
            return reject(result, ACL_REJECTED, null, null);
        }
        ACLResult finalAclResult = aclResult;
        AtomicBoolean databaseChangesApplied = new AtomicBoolean(false);
        try {
            session.withAudit(
                    plan.getAuditSql(),
                    () -> {
                        SQLQueryResult executed = executeTransaction(
                                connection,
                                plan,
                                finalAclResult,
                                transactionBoundary
                        );
                        databaseChangesApplied.set(true);
                        return executed;
                    }
            );
        } catch (RuntimeException auditFailure) {
            if (!databaseChangesApplied.get()) {
                throw auditFailure;
            }
            boolean databaseCommitted = transactionMode == TransactionMode.SERVICE_MANAGED;
            log.error(
                    "save changes database work completed but audit failed, dataView={}, table={}.{}, databaseCommitted={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    databaseCommitted,
                    auditFailure
            );
            return appliedResult(
                    result,
                    plan,
                    databaseCommitted,
                    false,
                    databaseCommitted
                            ? SAVE_CHANGES_AUDIT_FAILED_AFTER_COMMIT
                            : SAVE_CHANGES_AUDIT_FAILED_AFTER_APPLY
            );
        }
        return appliedResult(
                result,
                plan,
                transactionMode == TransactionMode.SERVICE_MANAGED,
                true,
                null
        );
    }

    private SaveChangesResult appliedResult(
            SaveChangesResult result,
            TableChangesPlan plan,
            boolean databaseCommitted,
            boolean auditSucceeded,
            String reason
    ) {
        result.setSuccess(true);
        result.setAllowed(true);
        result.setDatabaseChangesApplied(true);
        result.setDatabaseCommitted(databaseCommitted);
        result.setAuditSucceeded(auditSucceeded);
        result.setReason(reason);
        result.setChangeCount(plan.getChangeCount());
        // executeTransaction has already verified every command affected exactly one row.
        result.getStatements().forEach(item -> item.setAffectedRows(1));

        if (auditSucceeded) {
            log.info(
                    "save changes succeeded, dataView={}, table={}.{}, changeCount={}, databaseCommitted={}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    plan.getChangeCount(),
                    databaseCommitted
            );
        }
        return result;
    }

    private void recordRejectedCommand(Session session, TableChangesPlan plan, ACLResult aclResult) {
        CommandRecord commandRecord = new CommandRecord(plan.getAuditSql());
        commandRecord.setRiskLevel(aclResult.getRiskLevel());
        commandRecord.setCmdAclId(aclResult.getCmdAclId());
        commandRecord.setCmdGroupId(aclResult.getCmdGroupId());
        commandRecord.setError(ACL_REJECTED);
        try {
            session.recordCommand(commandRecord);
        } catch (RuntimeException e) {
            log.error(
                    "record rejected save changes command failed, dataView={}, table={}.{}",
                    plan.getDataView(),
                    plan.getSchema(),
                    plan.getTable(),
                    e
            );
        }
    }

    private TransactionBoundary transactionBoundary(SaveExecutionContext executionContext, DbType dbType) {
        if (executionContext.transactionMode() == TransactionMode.SERVICE_MANAGED) {
            return this.serviceManagedTransactionBoundary;
        }
        if (executionContext.transactionMode() != TransactionMode.USER_MANAGED ||
                executionContext.connectionOwnership() != ConnectionOwnership.QUERY_CONSOLE) {
            throw new IllegalArgumentException("USER_MANAGED transactions require QUERY_CONSOLE connection ownership");
        }
        return new UserManagedTransactionBoundary(SavepointControllers.forDbType(dbType));
    }

    SQLQueryResult executeTransaction(
            Connection connection,
            TableChangesPlan plan,
            ACLResult aclResult,
            TransactionBoundary transactionBoundary
    ) throws SQLException {
        return transactionBoundary.execute(connection, plan, () -> {
            for (int i = 0; i < plan.getCommands().size(); i++) {
                executeCommand(connection, plan, plan.getCommands().get(i), i);
            }
            return successQueryResult(plan, aclResult);
        });
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
            throw new UnexpectedAffectedRowsException(changeIndex, affectedRows);
        }
    }

    private SQLQueryResult successQueryResult(TableChangesPlan plan, ACLResult aclResult) {
        SQLQueryResult result = new SQLQueryResult(plan.getAuditSql());
        result.setHasResultSet(false);
        result.setUpdateCount(plan.getChangeCount());
        result.setAclResult(aclResult);
        return result;
    }

    private boolean isRejected(ACLResult aclResult) {
        return aclResult != null &&
                (aclResult.getRiskLevel() == Common.RiskLevel.Reject ||
                        aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject ||
                        aclResult.getRiskLevel() == Common.RiskLevel.ReviewCancel);
    }

    private ACLResult checkBatchACL(
            TableChangesPlan plan,
            Session session,
            Connection connection,
            ConnectionOwnership connectionOwnership
    ) {
        List<ACLResult> results = new ArrayList<>(plan.getAuditSqlList().size());
        boolean batch = plan.getAuditSqlList().size() > 1;
        String reviewBatchSql = batch ? plan.getAuditSql() : null;
        for (String sql : plan.getAuditSqlList()) {
            results.add(session.checkACLWithContext(
                    sql,
                    ACLCommandContext.planned(
                            connection,
                            connectionOwnership,
                            plan.getChangeCount(),
                            reviewBatchSql
                    )
            ));
        }
        return aggregateACLResults(results);
    }

    static ACLResult aggregateACLResults(List<ACLResult> results) {
        ACLResult aggregate = normalACLResult();
        boolean notify = false;
        boolean hasEffectiveResult = false;
        for (ACLResult result : results) {
            if (result == null) {
                continue;
            }
            notify |= result.isNotify();
            int resultPriority = riskPriority(result.getRiskLevel());
            int aggregatePriority = riskPriority(aggregate.getRiskLevel());
            if (!hasEffectiveResult || resultPriority > aggregatePriority ||
                    (resultPriority == aggregatePriority && result.isNotify() && !aggregate.isNotify())) {
                aggregate = copyACLResult(result);
                hasEffectiveResult = true;
            }
        }
        aggregate.setNotify(notify);
        return aggregate;
    }

    private static int riskPriority(Common.RiskLevel riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel) {
            case UNRECOGNIZED -> 7;
            case ReviewReject -> 6;
            case ReviewCancel -> 5;
            case Reject -> 4;
            case ReviewAccept -> 3;
            case Warning -> 2;
            case Normal -> 1;
        };
    }

    private static ACLResult normalACLResult() {
        ACLResult result = new ACLResult();
        result.setRiskLevel(Common.RiskLevel.Normal);
        return result;
    }

    private static ACLResult copyACLResult(ACLResult source) {
        ACLResult result = new ACLResult();
        result.setRiskLevel(source.getRiskLevel());
        result.setCmdAclId(source.getCmdAclId());
        result.setCmdGroupId(source.getCmdGroupId());
        result.setNotify(source.isNotify());
        return result;
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
        if (index < 0 || index >= plan.getCommands().size()) {
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
