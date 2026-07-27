package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.acl.ACLResult;

import java.sql.Connection;

public interface ACLFilter {
    String REVIEW_BATCH_SQL_ATTRIBUTE = ACLFilter.class.getName() + ".reviewBatchSql";

    ACLResult commandACLFilter(String command, Connection connection);
}
