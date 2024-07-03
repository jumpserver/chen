package org.jumpserver.chen.web.service;


import org.jumpserver.chen.framework.session.Session;

public interface SessionService {
    Session createNewSession(String token,String remoteAddr);
}
