package org.jumpserver.chen.framework.datasource.plan;

import org.jumpserver.chen.framework.i18n.MessageUtils;

public final class PlanI18n {
    private PlanI18n() {
    }

    public static String msg(String key, String english, Object... args) {
        return MessageUtils.getOrDefault(key, english, args);
    }
}
