package org.jumpserver.chen.web.service.impl;

import org.junit.jupiter.api.Test;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OceanBasePlatformSettingsTest {
    @Test
    void acceptsOnlyExplicitTenantModes() {
        for (var mode : new String[]{"mysql", "oracle"}) {
            var info = new DBConnectInfo();
            info.setDbType("oceanbase");
            JmsSessionService.applyPlatformSettings(info, Map.of("tenant_mode", mode));
            assertEquals(mode, info.getTenantMode());
        }

        var missing = new DBConnectInfo();
        missing.setDbType("oceanbase");
        assertThrows(IllegalArgumentException.class,
                () -> JmsSessionService.applyPlatformSettings(missing, Map.of()));

        var invalid = new DBConnectInfo();
        invalid.setDbType("oceanbase");
        assertThrows(IllegalArgumentException.class,
                () -> JmsSessionService.applyPlatformSettings(invalid, Map.of("tenant_mode", "unknown")));
    }
}
