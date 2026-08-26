package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.jms.CommandHandler;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.wisp.Common;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;

import java.util.concurrent.TimeUnit;

@Slf4j
public class CommandHandlerImpl implements CommandHandler {
    private static final long COMMAND_UPLOAD_TIMEOUT_SECONDS = 30;
    private final Common.Session session;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;


    public CommandHandlerImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub serviceBlockingStub) {
        this.session = session;
        this.serviceBlockingStub = serviceBlockingStub;
    }

    @Override
    public void recordCommand(CommandRecord commandRecord) {

        var reqBuilder = ServiceOuterClass.CommandRequest
                .newBuilder()
                .setSid(this.session.getId())
                .setOrgId(this.session.getOrgId())
                .setAsset(this.session.getAsset())
                .setAccount(this.session.getAccount())
                .setUser(this.session.getUser())
                .setTimestamp(System.currentTimeMillis() / 1000)
                .setInput(commandRecord.getInput())
                .setOutput(commandRecord.getOutput())
                .setRiskLevel(commandRecord.getRiskLevel());

        if (commandRecord.getCmdAclId() != null && commandRecord.getCmdGroupId() != null) {
            reqBuilder.setCmdAclId(commandRecord.getCmdAclId());
            reqBuilder.setCmdGroupId(commandRecord.getCmdGroupId());
        }

        var resp = this.serviceBlockingStub
                .withDeadlineAfter(COMMAND_UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .uploadCommand(reqBuilder.build());
        if (!resp.getStatus().getOk()) {
            throw new RuntimeException("upload command failed: " + resp.getStatus().getErr());
        }
    }



}
