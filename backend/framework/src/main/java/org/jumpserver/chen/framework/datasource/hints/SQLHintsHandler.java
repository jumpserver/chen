package org.jumpserver.chen.framework.datasource.hints;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface SQLHintsHandler {
    Map<String, List<String>> getHints(String nodeKey, String context) throws SQLException;
}
