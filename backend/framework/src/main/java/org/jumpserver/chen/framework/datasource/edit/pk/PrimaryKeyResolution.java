package org.jumpserver.chen.framework.datasource.edit.pk;

import lombok.Getter;

import java.util.List;

@Getter
public class PrimaryKeyResolution {
    private final List<String> primaryKeys;
    private final String readOnlyReason;

    private PrimaryKeyResolution(List<String> primaryKeys, String readOnlyReason) {
        this.primaryKeys = primaryKeys;
        this.readOnlyReason = readOnlyReason;
    }

    public static PrimaryKeyResolution primaryKeys(List<String> primaryKeys) {
        return new PrimaryKeyResolution(primaryKeys, null);
    }

    public static PrimaryKeyResolution readOnly(String reason) {
        return new PrimaryKeyResolution(List.of(), reason);
    }
}
