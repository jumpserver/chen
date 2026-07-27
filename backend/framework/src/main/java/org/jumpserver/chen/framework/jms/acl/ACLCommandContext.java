package org.jumpserver.chen.framework.jms.acl;

import org.jumpserver.chen.framework.datasource.edit.ConnectionOwnership;

import java.sql.Connection;
import java.util.Objects;
import java.util.OptionalInt;

public record ACLCommandContext(
        Connection connection,
        ConnectionOwnership connectionOwnership,
        OptionalInt affectedRows
) {
    public ACLCommandContext {
        Objects.requireNonNull(connectionOwnership, "connectionOwnership");
        Objects.requireNonNull(affectedRows, "affectedRows");
        if (affectedRows.isPresent() && affectedRows.getAsInt() < 0) {
            throw new IllegalArgumentException("affectedRows must not be negative");
        }
    }

    public static ACLCommandContext executionOwned(Connection connection) {
        return new ACLCommandContext(
                connection,
                ConnectionOwnership.EXECUTION_CONTEXT,
                OptionalInt.empty()
        );
    }

    public static ACLCommandContext queryConsoleOwned(Connection connection) {
        return new ACLCommandContext(
                Objects.requireNonNull(connection, "connection"),
                ConnectionOwnership.QUERY_CONSOLE,
                OptionalInt.empty()
        );
    }

    public static ACLCommandContext planned(
            Connection connection,
            ConnectionOwnership connectionOwnership,
            int affectedRows
    ) {
        return new ACLCommandContext(connection, connectionOwnership, OptionalInt.of(affectedRows));
    }
}
