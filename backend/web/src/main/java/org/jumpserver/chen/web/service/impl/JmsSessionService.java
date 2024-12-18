package org.jumpserver.chen.web.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.web.service.SessionService;
import org.jumpserver.wisp.Common;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

@Service
@Slf4j
public class JmsSessionService implements SessionService {
    @GrpcClient("wisp")
    private ServiceGrpc.ServiceBlockingStub serviceBlockingStub;

    public Session createNewSession(String token, String remoteAddr) {

        var tokenResp = this.getTokenResponse(token);
        var jmsSession = this.createJMSSession(tokenResp, remoteAddr);

        if (StringUtils.isNotBlank(tokenResp.getData().getFaceMonitorToken())) {
            var faceMonitorToken = tokenResp.getData().getFaceMonitorToken();

            var req = ServiceOuterClass.JoinFaceMonitorRequest.newBuilder()
                    .setFaceMonitorToken(faceMonitorToken)
                    .setSessionId(jmsSession.getId())
                    .build();

            var resp = serviceBlockingStub.joinFaceMonitor(req);
            if (!resp.getStatus().getOk()) {
                throw new RuntimeException("Create face monitor context failed");
            }
        }


        var datasource = this.createDatasource(tokenResp);
        var session = new JMSSession(jmsSession, datasource, remoteAddr, this.serviceBlockingStub, tokenResp);
        this.handleGateways(tokenResp, session, datasource);
        return session;
    }

    private void handleGateways(ServiceOuterClass.TokenResponse tokenResp, Session session, Datasource dataSource) {
        if (tokenResp.getData().getGatewaysCount() == 0) {
            return;
        }


        var req = ServiceOuterClass.ForwardRequest
                .newBuilder()
                .setHost(dataSource.getConnectInfo().getHost())
                .setPort(dataSource.getConnectInfo().getPort())
                .addAllGateways(tokenResp.getData().getGatewaysList())
                .build();

        var resp = this.serviceBlockingStub.createForward(req);

        dataSource.getConnectInfo().setProxyHost("127.0.0.1");
        dataSource.getConnectInfo().setProxyPort(resp.getPort());

        session.setGatewayId(resp.getId());
    }

    private void closeSession(Common.Session session) {
        var req = ServiceOuterClass
                .SessionFinishRequest
                .newBuilder()
                .setId(session.getId())
                .setDateEnd(Instant.now().getEpochSecond())
                .build();
        var resp = this.serviceBlockingStub.finishSession(req);

        if (!resp.getStatus().getOk()) {
            log.error("finish session failed: {}", resp.getStatus().getErr());
        }
    }

    private ServiceOuterClass.TokenResponse getTokenResponse(String token) {
        var tokenReq = ServiceOuterClass
                .TokenRequest
                .newBuilder()
                .setToken(token)
                .build();
        var tokenResp = this.serviceBlockingStub.getTokenAuthInfo(tokenReq);
        if (tokenResp.getStatus().getOk()) {
            return tokenResp;
        } else {
            throw new RuntimeException(tokenResp.getStatus().getErr());
        }
    }

    public static String getIPAddressType(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.getHostAddress().contains(":")) {
                return "IPv6";
            } else {
                return "IPv4";
            }
        } catch (UnknownHostException e) {
            return "Unknown";
        }
    }

    private Datasource createDatasource(ServiceOuterClass.TokenResponse tokenResp) {
        DBConnectInfo dbConnectInfo = new DBConnectInfo();

        var address = getIPAddressType(dbConnectInfo.getHost()).equals("IPv6") ?
                dbConnectInfo.getHost() : String.format("[%s]", dbConnectInfo.getHost());

        dbConnectInfo.setHost(address);
        dbConnectInfo.setPort(tokenResp.getData().getAsset().getProtocols(0).getPort());
        dbConnectInfo.setDbType(tokenResp.getData().getAsset().getProtocols(0).getName().toLowerCase());
        dbConnectInfo.setUser(tokenResp.getData().getAccount().getUsername());
        dbConnectInfo.setPassword(tokenResp.getData().getAccount().getSecret());
        dbConnectInfo.setDb(tokenResp.getData().getAsset().getSpecific().getDbName());

        var platformSettings = tokenResp.getData().getPlatform().getProtocols(0).getSettingsMap();
//
        if (platformSettings.containsKey("sysdba") && platformSettings.get("sysdba").equals("true")) {
            dbConnectInfo.getOptions().put("internal_logon", "sysdba");
        }

        if (platformSettings.containsKey("version")) {
            dbConnectInfo.getOptions().put("version", platformSettings.get("version"));
        }

        var asset = tokenResp.getData().getAsset();

        if (asset.getSpecific().getUseSsl()) {
            dbConnectInfo.getOptions().put("useSSL", true);
            dbConnectInfo.getOptions().put("verifyServerCertificate", !asset.getSpecific().getAllowInvalidCert());
            dbConnectInfo.getOptions().put("caCert", asset.getSpecific().getCaCert());
            dbConnectInfo.getOptions().put("clientCert", asset.getSpecific().getClientCert());
            dbConnectInfo.getOptions().put("clientKey", asset.getSpecific().getClientKey());
            dbConnectInfo.getOptions().put("pgSSLMode", asset.getSpecific().getPgSslMode());
        }
        return DatasourceFactory.fromConnectInfo(dbConnectInfo);
    }

    private Common.Session createJMSSession(ServiceOuterClass.TokenResponse tokenResp, String remoteAddr) {
        var jmsSession = Common.Session.newBuilder()
                .setUserId(tokenResp.getData().getUser().getId())
                .setUser(String.format("%s(%s)", tokenResp.getData().getUser().getName(), tokenResp.getData().getUser().getUsername()))
                .setAccountId(tokenResp.getData().getAccount().getId())
                .setAccount(String.format("%s(%s)", tokenResp.getData().getAccount().getName(), tokenResp.getData().getAccount().getUsername()))
                .setOrgId(tokenResp.getData().getAsset().getOrgId())
                .setAssetId(tokenResp.getData().getAsset().getId())
                .setAsset(tokenResp.getData().getAsset().getName())
                .setLoginFrom(Common.Session.LoginFrom.WT)
                .setProtocol(tokenResp.getData().getAsset().getProtocols(0).getName())
                .setDateStart(System.currentTimeMillis() / 1000)
                .setRemoteAddr(remoteAddr)
                .setTokenId(tokenResp.getData().getKeyId())
                .build();

        var sessionResp = this.serviceBlockingStub.createSession(
                ServiceOuterClass.SessionCreateRequest
                        .newBuilder()
                        .setData(jmsSession)
                        .build()
        );
        if (!sessionResp.getStatus().getOk()) {
            throw new RuntimeException(sessionResp.getStatus().getErr());
        }
        jmsSession = sessionResp.getData();
        return jmsSession;
    }
}
