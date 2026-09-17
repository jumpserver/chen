package org.jumpserver.chen.framework.session;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface MetadataQueryAuditFunction {
    List<Map<String, Object>> run() throws SQLException;
}
