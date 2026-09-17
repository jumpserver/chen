package org.jumpserver.chen.framework.datasource.edit;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.List;

public final class TableInsertability {
    private TableInsertability() {
    }

    public static boolean isInsertable(List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }

        boolean hasOrdinaryField = false;
        for (Field field : fields) {
            if (field == null) {
                return false;
            }
            if (field.isAutoIncrement() || field.isGenerated()) {
                continue;
            }
            hasOrdinaryField = true;
            if (!field.isInsertable()) {
                return false;
            }
        }
        return hasOrdinaryField;
    }
}
