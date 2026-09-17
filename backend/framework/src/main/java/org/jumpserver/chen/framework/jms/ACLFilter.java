package org.jumpserver.chen.framework.jms;

import org.jumpserver.chen.framework.jms.acl.ACLCommandContext;
import org.jumpserver.chen.framework.jms.acl.ACLResult;

import java.sql.Connection;

public interface ACLFilter {
    default ACLResult commandACLFilter(String command, Connection connection) {
        ACLCommandContext context = connection == null
                ? ACLCommandContext.executionOwned(null)
                : ACLCommandContext.queryConsoleOwned(connection);
        return this.commandACLFilterWithContext(command, context);
    }

    ACLResult commandACLFilterWithContext(String command, ACLCommandContext context);
}
