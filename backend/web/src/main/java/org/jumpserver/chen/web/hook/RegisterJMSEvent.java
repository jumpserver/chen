package org.jumpserver.chen.web.hook;

import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.web.config.MockConfig;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
public class RegisterJMSEvent {

    @GrpcClient("wisp")
    private ServiceGrpc.ServiceBlockingStub serviceBlockingStub;
    @Autowired
    private MockConfig mockConfig;

    @PostConstruct
    public void clearZombieSession() {
        if (this.mockConfig.isEnable()) {
            return;
        }

        var currentDir = System.getProperty("user.dir");
        var filePath = Path.of(currentDir, "data/replay");

        log.info("Scan remain replay: {}", filePath);

        var req = ServiceOuterClass.RemainReplayRequest
                .newBuilder()
                .setReplayDir(filePath.toString())
                .build();
        var resp = this.serviceBlockingStub.scanRemainReplays(req);
        if (!resp.getStatus().getOk()) {
            log.error("Scan remain replay error: {}", resp.getStatus().getErr());
        } else {
            log.info("Scan remain replay success");
        }
    }


    @Async
    @PostConstruct
    public void startSessionKiller() {
        if (this.mockConfig.isEnable()) {
            return;
        }
        this.waitForKillSessionMessage();
    }


    public static StreamObserver<ServiceOuterClass.FinishedTaskRequest> requestObserver;

    private void waitForKillSessionMessage() {
        requestObserver = ServiceGrpc
                .newStub(this.serviceBlockingStub.getChannel())
                .dispatchTask(new StreamObserver<>() {
                    @Override
                    public void onNext(ServiceOuterClass.TaskResponse taskResponse) {
                        JMSSession targetSession = null;
                        for (var session : SessionManager.getStore().values()) {
                            if (session instanceof JMSSession) {
                                if (((JMSSession) session).getJmsSession().getId().equals(taskResponse.getTask().getSessionId())) {
                                    targetSession = (JMSSession) session;
                                    break;
                                }
                            }
                        }
                        if (targetSession != null) {
                            switch (taskResponse.getTask().getAction()) {
                                case KillSession ->
                                        targetSession.close("SessionClosedBy","admin_terminate", taskResponse.getTask().getTerminatedBy());

                                case LockSession -> targetSession.lockSession(taskResponse.getTask().getCreatedBy());
                                case UnlockSession ->
                                        targetSession.unloadSession(taskResponse.getTask().getCreatedBy());
                            }
                            var req = ServiceOuterClass.FinishedTaskRequest
                                    .newBuilder()
                                    .setTaskId(taskResponse.getTask().getId())
                                    .build();
                            requestObserver.onNext(req);
                        }

                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.error("waitSessionMessage error", throwable);

                    }

                    @Override
                    public void onCompleted() {
                        log.error("waitSessionMessage completed");
                    }
                });
    }
}
