package org.jumpserver.chen.framework.datasource.plan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

public final class ExecutionPlanJson {
    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .create();

    private ExecutionPlanJson() {
    }

    public static JsonElement toJsonTree(ExecutionPlan plan) {
        return GSON.toJsonTree(plan);
    }

    public static String toJson(ExecutionPlan plan) {
        return GSON.toJson(plan);
    }

    public static ExecutionPlan fromJson(String json) {
        return GSON.fromJson(json, ExecutionPlan.class);
    }
}
