package org.jumpserver.chen.framework.datasource.edit;

import com.alibaba.druid.DbType;
import lombok.Data;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.List;

@Data
public class TableEditContext {
    private String dataViewTitle;
    private String schema;
    private String table;
    private List<Field> fields;
    private DbType dbType;
    private boolean tableBrowse;

    public TableEditContext(String dataViewTitle, String schema, String table, List<Field> fields, DbType dbType) {
        this(dataViewTitle, schema, table, fields, dbType, false);
    }

    public TableEditContext(
            String dataViewTitle,
            String schema,
            String table,
            List<Field> fields,
            DbType dbType,
            boolean tableBrowse
    ) {
        this.dataViewTitle = dataViewTitle;
        this.schema = schema;
        this.table = table;
        this.fields = fields;
        this.dbType = dbType;
        this.tableBrowse = tableBrowse;
    }
}
