package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.edit.ConnectionOwnership;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.ACLFilter;
import org.jumpserver.chen.framework.jms.acl.ACLCommandContext;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.DialogHandle;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.wisp.Common;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;

import java.util.List;
import java.util.OptionalInt;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public class ACLFilterImpl implements ACLFilter {
    private static final long REVIEW_DECISION_TIMEOUT_SECONDS = 300;
    private static final long REVIEW_RPC_TIMEOUT_SECONDS = 30;

    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;
    private final List<Common.CommandACL> commandACLs;
    private final Common.Session session;


    public ACLFilterImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub serviceBlockingStub, List<Common.CommandACL> commandACLs) {
        this.serviceBlockingStub = serviceBlockingStub;
        this.commandACLs = commandACLs;
        this.session = session;
    }

    private static final String REJECT_MESSAGE = "reject by acl rule";

    @Override
    public ACLResult commandACLFilterWithContext(String command, ACLCommandContext context) {
        var result = new ACLResult();
        var acl = this.matchRule(command, result);

        if (acl == null) {
            result.setRiskLevel(Common.RiskLevel.Normal);
            return result;
        }

        switch (acl.getAction()) {
            case Accept -> {
                result.setRiskLevel(Common.RiskLevel.Normal);
            }
            case Warning -> {
                result.setRiskLevel(Common.RiskLevel.Warning);
            }
            case NotifyWarning -> {
                result.setRiskLevel(Common.RiskLevel.Warning);
                result.setNotify(true);
            }
            case Reject -> {
                result.setRiskLevel(Common.RiskLevel.Reject);
            }
            case Review -> {
                var decisionLatch = new CountDownLatch(1);
                var resultLatch = new CountDownLatch(1);
                AtomicReference<Exception> exception = new AtomicReference<>(null);
                AtomicBoolean submitted = new AtomicBoolean(false);
                AtomicReference<DialogHandle> dialogHandle = new AtomicReference<>(DialogHandle.NOOP);
                var controller = SessionManager.getCurrentSession().getController();
                String dialogOwner = controller.getDialogOwner();

                var dialog = new Dialog(MessageUtils.get("CommandReview"));
                dialog.setBody(MessageUtils.get("CommandReviewMessage"));

                dialog.addButton(new Button(MessageUtils.get("Submit"), "submit", () -> {
                    if (!submitted.compareAndSet(false, true)) {
                        return;
                    }
                    dialogHandle.get().close();
                    var token = SessionManager.getContextToken();
                    new Thread(() -> {
                        SessionManager.setContext(token);
                        controller.bindDialogOwner(dialogOwner);
                        try {
                            if (controller.isDialogOwnerCancelled(dialogOwner)) {
                                throw new RuntimeException(MessageUtils.get("UserCancelCommandReviewError"));
                            }
                            this.createAndWaitTicket(command, acl, context);
                        } catch (Exception e) {
                            exception.set(e);
                        } finally {
                            resultLatch.countDown();
                            controller.clearDialogOwner();
                        }
                    }).start();
                    decisionLatch.countDown();
                }));
                dialog.addButton(new Button(MessageUtils.get("Cancel"), "cancel", () -> {
                    exception.compareAndSet(
                            null,
                            new RuntimeException(MessageUtils.get("UserCancelCommandReviewError"))
                    );
                    decisionLatch.countDown();
                    resultLatch.countDown();
                }));

                dialogHandle.set(controller.showDialog(dialog, () -> {
                    exception.compareAndSet(
                            null,
                            new RuntimeException(MessageUtils.get("UserCancelCommandReviewError"))
                    );
                    decisionLatch.countDown();
                    resultLatch.countDown();
                }));

                try {
                    if (!decisionLatch.await(REVIEW_DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        exception.compareAndSet(
                                null,
                                new RuntimeException(MessageUtils.get("CommandReviewTimeoutError"))
                        );
                        dialogHandle.get().cancel();
                    }
                    if (submitted.get() && exception.get() == null &&
                            !resultLatch.await(WAIT_TICKET_TIMEOUT, TimeUnit.MILLISECONDS)) {
                        exception.compareAndSet(
                                null,
                                new RuntimeException(MessageUtils.get("CommandReviewTimeoutError"))
                        );
                        controller.cancelCurrentDialog(dialogOwner);
                    }
                    if (!submitted.get() || exception.get() != null) {
                        result.setRiskLevel(Common.RiskLevel.ReviewReject);
                    } else {
                        result.setRiskLevel(Common.RiskLevel.ReviewAccept);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    controller.cancelCurrentDialog(dialogOwner);
                    result.setRiskLevel(Common.RiskLevel.ReviewReject);
                } finally {
                    dialogHandle.get().close();
                }
            }
        }


        return result;
    }


    private void createAndWaitTicket(String command, Common.CommandACL commandACL, ACLCommandContext context) {
        OptionalInt affectedRows = this.estimateAffectedRows(command, context);

        var input = reviewTicketCommand(command, context);
        String affectedRowsValue = affectedRows.isPresent()
                ? Integer.toString(affectedRows.getAsInt())
                : "unknown";
        input = String.format("Affected rows: %s\n%s", affectedRowsValue, input);

        var req = ServiceOuterClass.CommandConfirmRequest
                .newBuilder()
                .setCmd(input)
                .setSessionId(this.session.getId())
                .setCmdAclId(commandACL.getId())
                .build();
        var resp = this.serviceBlockingStub
                .withDeadlineAfter(REVIEW_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .createCommandTicket(req);
        if (!resp.getStatus().getOk()) {
            throw new RuntimeException("create command ticket failed: " + resp.getStatus().getErr());
        }
        this.waitForTicketStatusChange(resp.getInfo());
    }

    OptionalInt estimateAffectedRows(String command, ACLCommandContext context) {
        if (context.affectedRows().isPresent()) {
            return context.affectedRows();
        }
        if (context.connectionOwnership() == ConnectionOwnership.QUERY_CONSOLE) {
            return OptionalInt.empty();
        }

        try {
            var sqlActuator = SessionManager.getCurrentSession()
                    .getDatasource()
                    .getConnectionManager()
                    .getSqlActuator();
            if (context.connection() != null) {
                sqlActuator = sqlActuator.withConnection(context.connection());
            }
            int affectedRows = sqlActuator.getAffectedRows(SQL.of(command));
            return affectedRows < 0 ? OptionalInt.empty() : OptionalInt.of(affectedRows);
        } catch (Exception e) {
            log.warn("get affected rows failed: {}", e.getClass().getSimpleName());
            return OptionalInt.empty();
        }
    }

    private String reviewTicketCommand(String command, ACLCommandContext context) {
        String batchSql = context.reviewBatchSql();
        if (batchSql == null || batchSql.isBlank() || batchSql.equals(command)) {
            return command;
        }
        return String.format("Batch SQL:\n%s\n\nReview-triggering SQL:\n%s", batchSql, command);
    }


    private DialogHandle openCommandReviewEvent(Runnable cancel) {
        var dialog = new Dialog(MessageUtils.get("CommandReview"));
        dialog.setBody(MessageUtils.get("WaitCommandReviewMessage"));
        dialog.addButton(new Button(MessageUtils.get("Cancel"), "cancel", () -> {
            cancel.run();
        }));
        return SessionManager.getCurrentSession().getController().showDialog(dialog, cancel);
    }

    private static final long WAIT_TICKET_TIMEOUT = 30 * 60 * 1000;
    private static final long WAIT_TICKET_INTERVAL = 5 * 1000;

    private void waitForTicketStatusChange(ServiceOuterClass.TicketInfo ticketInfo) {

        CountDownLatch cdl = new CountDownLatch(1);
        Timer timer = new Timer();

        final AtomicBoolean ticketClosed = new AtomicBoolean(false);
        AtomicReference<RuntimeException> exception = new AtomicReference<>(null);

        DialogHandle dialogHandle = this.openCommandReviewEvent(() -> {
            exception.set(new RuntimeException(MessageUtils.get("UserCancelCommandReviewError")));
            cdl.countDown();
            timer.cancel();
        });

        try {
            var stub = this.serviceBlockingStub;

            var token = SessionManager.getContextToken();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SessionManager.setContext(token);

                    var checkRequest = ServiceOuterClass
                            .TicketRequest.newBuilder()
                            .setReq(ticketInfo.getCheckReq())
                            .build();

                    try {
                        var checkResponse = stub
                                .withDeadlineAfter(REVIEW_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                .checkTicketState(checkRequest);

                        if (!checkResponse.getStatus().getOk()) {
                            throw new RuntimeException(
                                    "Failed to check ticket status: " + checkResponse.getStatus().getErr()
                            );
                        }

                        switch (checkResponse.getData().getState()) {
                            case Approved -> {
                                ticketClosed.set(true);
                                timer.cancel();
                                cdl.countDown();
                            }
                            case Rejected, Closed -> {
                                ticketClosed.set(true);
                                exception.set(new RuntimeException(MessageUtils.get(
                                        "CommandReviewRejectBy",
                                        checkResponse.getData().getProcessor()
                                )));
                                timer.cancel();
                                cdl.countDown();
                            }
                        }
                    } catch (RuntimeException e) {
                        exception.compareAndSet(null, e);
                        timer.cancel();
                        cdl.countDown();
                    }
                }
            }, 0, WAIT_TICKET_INTERVAL);

            try {
                if (!cdl.await(WAIT_TICKET_TIMEOUT, TimeUnit.MILLISECONDS)) {
                    exception.compareAndSet(
                            null,
                            new RuntimeException(MessageUtils.get("CommandReviewTimeoutError"))
                    );
                }
                if (exception.get() != null) {
                    throw exception.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } finally {
            timer.cancel();
            dialogHandle.close();
            if (!ticketClosed.get()) {
                this.closeTicket(ticketInfo);
            }
        }
    }

    private void closeTicket(ServiceOuterClass.TicketInfo ticketInfo) {
        var cancelRequest = ServiceOuterClass.TicketRequest.newBuilder()
                .setReq(ticketInfo.getCancelReq())
                .build();
        var cancelResponse = this.serviceBlockingStub
                .withDeadlineAfter(REVIEW_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .cancelTicket(cancelRequest);
        if (!cancelResponse.getStatus().getOk()) {
            log.error("close ticket failed: {}", cancelResponse.getStatus().getErr());
        }
    }


    private Common.CommandACL matchRule(String command, ACLResult result) {
        for (Common.CommandACL commandACL : this.commandACLs) {
            for (Common.CommandGroup commandGroup : commandACL.getCommandGroupsList()) {

                int flags = Pattern.UNICODE_CASE;
                if (commandGroup.getIgnoreCase()) {
                    flags |= Pattern.CASE_INSENSITIVE;
                }
                try {
                    Pattern pattern = Pattern.compile(commandGroup.getPattern(), flags);
                    if (pattern.matcher(command.toLowerCase()).find()) {
                        result.setCmdAclId(commandACL.getId());
                        result.setCmdGroupId(commandGroup.getId());
                        return commandACL;
                    }
                } catch (PatternSyntaxException e) {
                    log.error("invalid pattern: {}", commandGroup.getPattern(), e);
                }
            }
        }
        return null;
    }
}
