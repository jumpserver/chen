package org.jumpserver.chen.web.service.impl;

import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.web.config.MockConfig;
import org.jumpserver.chen.web.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MockSessionService implements SessionService {

    @Autowired
    private MockConfig mockConfig;

    @Override
    public Session createNewSession(String token, String remoteAddr) {
        var dbType = token != null ? token.toLowerCase() : "mysql";
        var connectInfo = mockConfig.getMockDBInfo(dbType);
        var ds = DatasourceFactory
                .fromConnectInfo(connectInfo);
        return new BaseSession(ds, remoteAddr);
    }
}
