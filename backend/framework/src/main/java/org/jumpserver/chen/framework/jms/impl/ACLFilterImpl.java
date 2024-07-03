package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.ACLFilter;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Button;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public class ACLFilterImpl implements ACLFilter {

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
    public ACLResult commandACLFilter(String command, Connection connection) {
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
            case Reject -> {
                result.setRiskLevel(Common.RiskLevel.Reject);
            }
            case Review -> {
                var countDownLatch = new CountDownLatch(1);
                AtomicReference<Exception> exception = new AtomicReference<>(null);

                var dialog = new Dialog(MessageUtils.get("msg.dialog.title.command_review"));
                dialog.setBody(MessageUtils.get("msg.dialog.message.command_review"));

                dialog.addButton(new Button(MessageUtils.get("btn.label.submit"), "submit", () -> {

                    var token = SessionManager.getContextToken();
                    new Thread(() -> {
                        SessionManager.setContext(token);
                        try {
                            this.createAndWaitTicket(command, acl, connection);
                        } catch (Exception e) {
                            exception.set(e);
                        } finally {
                            countDownLatch.countDown();
                        }
                    }).start();
                }));
                dialog.addButton(new Button(MessageUtils.get("btn.label.cancel"), "cancel", () -> {
                    exception.set(new RuntimeException(MessageUtils.get("msg.error.user_cancel_command_review")));
                    countDownLatch.countDown();
                }));

                SessionManager.getCurrentSession().getController().showDialog(dialog);

                try {
                    countDownLatch.await();
                    if (exception.get() != null) {
                        result.setRiskLevel(Common.RiskLevel.ReviewReject);
                    } else {
                        result.setRiskLevel(Common.RiskLevel.ReviewAccept);
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    SessionManager.getCurrentSession().getController().closeDialog();
                }
            }
        }


        return result;
    }


    private void createAndWaitTicket(String command, Common.CommandACL commandACL, Connection connection) {
        var affectRows = 0;

        var sqlActuator = SessionManager.getCurrentSession()
                .getDatasource()
                .getConnectionManager()
                .getSqlActuator();
        if (connection != null) {
            sqlActuator = sqlActuator.withConnection(connection);
        }
        try {
            affectRows = sqlActuator.getAffectedRows(SQL.of(command));
        } catch (SQLException e) {
            log.error("get affected rows failed", e);
        }

        var input = command;
        if (affectRows != -1) {
            input = String.format("Affected rows: %d\n%s", affectRows, command);
        }


        var req = ServiceOuterClass.CommandConfirmRequest
                .newBuilder()
                .setCmd(input)
                .setSessionId(this.session.getId())
                .setCmdAclId(commandACL.getId())
                .build();
        var resp = this.serviceBlockingStub.createCommandTicket(req);
        if (!resp.getStatus().getOk()) {
            throw new RuntimeException("create command ticket failed: " + resp.getStatus().getErr());
        }
        this.waitForTicketStatusChange(command, resp.getInfo());
    }


    private void openCommandReviewEvent(Runnable cancel, String command, long startTIme, long endTime) {
        var dialog = new Dialog(MessageUtils.get("msg.dialog.title.command_review"));
        dialog.setBody(MessageUtils.get("msg.dialog.message.wait_command_review"));
        dialog.addButton(new Button(MessageUtils.get("btn.label.cancel"), "cancel", () -> {
            cancel.run();
            SessionManager.getCurrentSession().getController().closeDialog();
        }));
        SessionManager.getCurrentSession().getController().showDialog(dialog);
    }

    private void closeCommandReviewEvent() {
        SessionManager.getCurrentSession().getController().closeDialog();
    }

    private static final long WAIT_TICKET_TIMEOUT = 30 * 60 * 1000;
    private static final long WAIT_TICKET_INTERVAL = 5 * 1000;

    private void waitForTicketStatusChange(String cmd, ServiceOuterClass.TicketInfo ticketInfo) {

        long startTime = System.currentTimeMillis();
        long endTime = startTime + WAIT_TICKET_TIMEOUT;

        CountDownLatch cdl = new CountDownLatch(1);
        Timer timer = new Timer();

        final AtomicBoolean ticketClosed = new AtomicBoolean(false);
        AtomicReference<RuntimeException> exception = new AtomicReference<>(null);

        this.openCommandReviewEvent(() -> {
            exception.set(new RuntimeException(MessageUtils.get("msg.error.user_cancel_command_review")));
            cdl.countDown();
            timer.cancel();

        }, cmd, startTime, endTime);

        try {
            var stub = this.serviceBlockingStub;

            var token = SessionManager.getContextToken();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    SessionManager.setContext(token);

                    if (System.currentTimeMillis() > endTime) {
                        exception.set(new RuntimeException(MessageUtils.get("msg.error.command_review_timeout")));
                        timer.cancel();
                        cdl.countDown();
                    }

                    var checkRequest = ServiceOuterClass
                            .TicketRequest.newBuilder()
                            .setReq(ticketInfo.getCheckReq())
                            .build();

                    var checkResponse = stub.checkTicketState(checkRequest);

                    if (!checkResponse.getStatus().getOk()) {
                        throw new RuntimeException("Failed to check ticket status: " + checkResponse.getStatus().getErr());
                    }

                    switch (checkResponse.getData().getState()) {
                        case Approved -> {
                            ticketClosed.set(true);
                            timer.cancel();
                            cdl.countDown();
                        }
                        case Rejected, Closed -> {
                            ticketClosed.set(true);
                            exception.set(new RuntimeException(MessageUtils.get("msg.error.command_review_reject", checkResponse.getData().getProcessor())));
                            timer.cancel();
                            cdl.countDown();
                        }
                    }
                }
            }, 0, WAIT_TICKET_INTERVAL);

            try {
                cdl.await();
                if (exception.get() != null) {
                    throw exception.get();
                }
            } catch (InterruptedException e) {
                log.error("wait for ticket status change failed: {}", e.getMessage());
            }
        } finally {
            this.closeCommandReviewEvent();
            if (!ticketClosed.get()) {
                this.closeTicket(ticketInfo);
            }
        }
    }

    private void closeTicket(ServiceOuterClass.TicketInfo ticketInfo) {
        var cancelRequest = ServiceOuterClass.TicketRequest.newBuilder()
                .setReq(ticketInfo.getCancelReq())
                .build();
        var cancelResponse = this.serviceBlockingStub.cancelTicket(cancelRequest);
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
