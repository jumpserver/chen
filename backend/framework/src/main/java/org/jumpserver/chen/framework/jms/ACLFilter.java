package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.acl.ACLResult;

import java.sql.Connection;

public interface ACLFilter {
    ACLResult commandACLFilter(String command, Connection connection);
}
