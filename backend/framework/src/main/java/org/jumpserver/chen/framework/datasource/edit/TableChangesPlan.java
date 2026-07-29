package org.jumpserver.chen.framework.datasource.edit;

import lombok.Data;
import org.jumpserver.chen.framework.datasource.edit.command.PreparedTableChangeCommand;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableChangesPlan {
    private String dataView;
    private String schema;
    private String table;
    private int changeCount;
    private int updateCount;
    private int insertCount;
    private int deleteCount;
    private List<PreparedTableChangeCommand> commands = new ArrayList<>();
    private List<String> auditSqlList = new ArrayList<>();
    private String auditSql;
}
