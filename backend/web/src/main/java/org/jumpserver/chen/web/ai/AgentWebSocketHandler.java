package org.jumpserver.chen.web.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_CLIENT_MESSAGE_BYTES = 320 * 1024;
    private static final int MAX_TOOL_RESULT_BYTES = 256 * 1024;
    private static final String AGENT_BINDING_META_KEY = "com.jumpserver/agent";
    private static final String SQL_CONTEXT_META_KEY = "com.jumpserver/sqlContext";
    private static final String SQL_OPERATION_META_KEY = "com.jumpserver/sqlOperation";
    private static final Set<String> OPERATIONS = Set.of("generate", "explain", "repair");

    private final SqlAgentToolService toolService;
    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor toolExecutor = new ThreadPoolExecutor(
            2,
            4,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "chen-agent-tool");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public AgentWebSocketHandler(SqlAgentToolService toolService) {
        this.toolService = toolService;
        this.toolExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) {
        String token = (String) webSocket.getAttributes().get("token");
        SessionManager.setContext(token);
        JMSSession session = currentSession(token);
        if (session == null) {
            sendProtocolError(webSocket, "session_closed", "The database session is not active");
            return;
        }
        sendManifest(webSocket, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) {
        String token = (String) webSocket.getAttributes().get("token");
        SessionManager.setContext(token);
        if (message.getPayloadLength() > MAX_CLIENT_MESSAGE_BYTES) {
            sendProtocolError(webSocket, "invalid_request", "Agent tool request is too large");
            return;
        }
        JMSSession session = currentSession(token);
        if (session == null) {
            sendProtocolError(webSocket, "session_closed", "The database session is not active");
            closeWebSocket(webSocket);
            return;
        }

        JsonObject packet;
        try {
            packet = JsonParser.parseString(message.getPayload()).getAsJsonObject();
        } catch (RuntimeException e) {
            sendProtocolError(webSocket, "invalid_request", "Invalid agent tool request");
            return;
        }
        String type = stringValue(packet, "type", 64);
        switch (type) {
            case "mcp.request" -> handleToolRequest(webSocket, token, session, packet);
            case "mcp.cancel" -> handleToolCancel(webSocket, session, packet);
            default -> sendProtocolError(webSocket, "invalid_request", "Unsupported agent tool request");
        }
    }

    private void sendManifest(WebSocketSession webSocket, JMSSession session) {
        var identity = session.getJmsSession();
        var connectInfo = session.getDatasource().getConnectInfo();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("protocol", identity.getProtocol());
        context.put("asset_id", identity.getAssetId());
        context.put("asset_name", identity.getAsset());
        context.put("platform_type", StringUtils.defaultString(connectInfo.getDbType()));
        context.put("database", StringUtils.defaultString(connectInfo.getDb()));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", PROTOCOL_VERSION);
        manifest.put("resource_session_id", identity.getId());
        manifest.put("profile", "sql");
        manifest.put("revision", 1);
        manifest.put("context", context);
        manifest.put("tools", toolDefinitions());
        new PacketIO(webSocket).sendPacket("mcp.manifest", manifest);
    }

    private void handleToolRequest(
            WebSocketSession webSocket,
            String token,
            JMSSession session,
            JsonObject packet
    ) {
        String requestID = "";
        try {
            JsonObject request = rpcData(packet, session);
            requestID = stringValue(request, "id", 128);
            if (requestID.isBlank() || !"2.0".equals(stringValue(request, "jsonrpc", 8))
                    || !"tools/call".equals(stringValue(request, "method", 32))) {
                throw new IllegalArgumentException("Invalid MCP tool request");
            }
            JsonObject params = objectValue(request, "params");
            String toolName = stringValue(params, "name", 128).toLowerCase(Locale.ROOT);
            JsonObject arguments = objectValue(params, "arguments");
            JsonObject metadata = objectValue(params, "_meta");
            validateBinding(metadata, session);
            JsonElement context = metadata.get(SQL_CONTEXT_META_KEY);
            String operation = stringValue(metadata, SQL_OPERATION_META_KEY, 32).toLowerCase(Locale.ROOT);
            if (context == null || !context.isJsonObject() || !OPERATIONS.contains(operation)) {
                throw new IllegalArgumentException("Invalid SQL editor binding");
            }

            String taskKey = taskKey(webSocket, requestID);
            String finalRequestID = requestID;
            FutureTask<Void> future = new FutureTask<>(() -> {
                try {
                    SessionManager.setContext(token);
                    var resolved = toolService.resolveRequestContext(session, GSON.toJson(context), operation);
                    String resultJSON = toolService.execute(session, resolved, toolName, GSON.toJson(arguments));
                    if (resultJSON.length() > MAX_TOOL_RESULT_BYTES) {
                        throw new IllegalStateException("Database tool result is too large");
                    }
                    sendToolResult(webSocket, session, finalRequestID, resultJSON);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    sendToolError(webSocket, session, finalRequestID, -32602, e.getMessage());
                } catch (SQLException e) {
                    log.warn("Chen database agent tool failed, sessionId={}, tool={}",
                            session.getJmsSession().getId(), toolName, e);
                    sendToolError(webSocket, session, finalRequestID, -32603, "Database metadata request failed");
                } catch (RuntimeException e) {
                    log.warn("Chen agent tool failed, sessionId={}, tool={}",
                            session.getJmsSession().getId(), toolName, e);
                    sendToolError(webSocket, session, finalRequestID, -32603, "Database tool failed");
                } finally {
                    tasks.remove(taskKey);
                }
                return null;
            });
            Future<?> existing = tasks.putIfAbsent(taskKey, future);
            if (existing != null) {
                sendToolError(webSocket, session, requestID, -32600, "Duplicate MCP request id");
                return;
            }
            try {
                toolExecutor.execute(future);
            } catch (RejectedExecutionException e) {
                tasks.remove(taskKey, future);
                future.cancel(true);
                throw e;
            }
        } catch (RejectedExecutionException e) {
            sendToolError(webSocket, session, requestID, -32000, "Database tool queue is full");
        } catch (IllegalArgumentException e) {
            sendToolError(webSocket, session, requestID, -32602, e.getMessage());
        }
    }

    private void handleToolCancel(WebSocketSession webSocket, JMSSession session, JsonObject packet) {
        String requestID = "";
        try {
            JsonObject request = rpcData(packet, session);
            if (!"2.0".equals(stringValue(request, "jsonrpc", 8))
                    || !"notifications/cancelled".equals(stringValue(request, "method", 64))) {
                throw new IllegalArgumentException("Invalid MCP cancellation");
            }
            requestID = stringValue(objectValue(request, "params"), "requestId", 128);
            if (requestID.isBlank()) {
                throw new IllegalArgumentException("Invalid MCP cancellation id");
            }
            Future<?> future = tasks.remove(taskKey(webSocket, requestID));
            boolean cancelled = future != null && future.cancel(true);
            sendRPC(webSocket, session, "mcp.cancel_result", Map.of(
                    "jsonrpc", "2.0",
                    "id", requestID,
                    "result", Map.of("cancelled", cancelled)
            ));
        } catch (IllegalArgumentException e) {
            sendToolError(webSocket, session, requestID, -32602, e.getMessage());
        }
    }

    private static JsonObject rpcData(JsonObject packet, JMSSession session) {
        if (numberValue(packet, "version") != PROTOCOL_VERSION
                || !session.getJmsSession().getId().equals(stringValue(packet, "resource_session_id", 128))) {
            throw new IllegalArgumentException("MCP resource binding does not match");
        }
        JsonElement data = packet.get("data");
        if (data != null && data.isJsonPrimitive() && data.getAsJsonPrimitive().isString()) {
            data = JsonParser.parseString(data.getAsString());
        }
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("Invalid MCP payload");
        }
        return data.getAsJsonObject();
    }

    private static void validateBinding(JsonObject metadata, JMSSession session) {
        JsonElement value = metadata.get(AGENT_BINDING_META_KEY);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Agent tool binding is missing");
        }
        JsonObject binding = value.getAsJsonObject();
        if (!session.getJmsSession().getId().equals(stringValue(binding, "resource_session_id", 128))
                || numberValue(binding, "revision") != 1
                || stringValue(binding, "tool_call_id", 128).isBlank()) {
            throw new IllegalArgumentException("Agent tool binding does not match");
        }
    }

    private void sendToolResult(
            WebSocketSession webSocket,
            JMSSession session,
            String requestID,
            String resultJSON
    ) {
        JsonObject structuredContent = JsonParser.parseString(resultJSON).getAsJsonObject();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", resultJSON)));
        result.put("structuredContent", structuredContent);
        sendRPC(webSocket, session, "mcp.response", Map.of(
                "jsonrpc", "2.0", "id", requestID, "result", result
        ));
    }

    private static void sendToolError(
            WebSocketSession webSocket,
            JMSSession session,
            String requestID,
            int code,
            String message
    ) {
        sendRPC(webSocket, session, "mcp.response", Map.of(
                "jsonrpc", "2.0",
                "id", StringUtils.defaultString(requestID),
                "error", Map.of("code", code, "message", StringUtils.defaultIfBlank(message, "Database tool failed"))
        ));
    }

    private static void sendRPC(
            WebSocketSession webSocket,
            JMSSession session,
            String type,
            Map<String, Object> response
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", PROTOCOL_VERSION);
        data.put("resource_session_id", session.getJmsSession().getId());
        data.putAll(response);
        new PacketIO(webSocket).sendPacket(type, data);
    }

    private static List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool(
                "read_sql_context",
                "Read SQL editor context",
                "Read Chen-verified dialect, connection scope, active editor target, SQL analysis and last error. "
                        + "This never reads business rows.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"maxProperties\":0}"
        ));
        tools.add(tool(
                "inspect_schema",
                "Inspect database schema",
                "Inspect bounded table, column, key, index and comment metadata in the active schema. "
                        + "Provide a table-name query or up to eight exact tables; query * performs bounded discovery.",
                "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"required\":[\"query\",\"schema\",\"tables\"],\"properties\":{"
                        + "\"query\":{\"type\":\"string\",\"maxLength\":1024},"
                        + "\"schema\":{\"type\":\"string\",\"maxLength\":1024},"
                        + "\"tables\":{\"type\":\"array\",\"maxItems\":8,"
                        + "\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1024}}}}"
        ));
        tools.add(tool(
                "validate_sql",
                "Validate SQL draft",
                "Parse SQL locally in Chen and return statement count, type, referenced objects and risk. "
                        + "This never executes SQL.",
                "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"sql\"],"
                        + "\"properties\":{\"sql\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":131072}}}"
        ));
        tools.add(tool(
                "propose_sql",
                "Propose SQL draft",
                "Validate exactly one SQL statement and prepare a draft for explicit user review. "
                        + "This never edits the editor or executes SQL.",
                "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"required\":[\"sql\",\"explanation\"],\"properties\":{"
                        + "\"sql\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":131072},"
                        + "\"explanation\":{\"type\":\"string\",\"maxLength\":4096}}}"
        ));
        return tools;
    }

    private static Map<String, Object> tool(
            String name,
            String title,
            String description,
            String inputSchema
    ) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("title", title);
        definition.put("description", description);
        definition.put("inputSchema", JsonParser.parseString(inputSchema).getAsJsonObject());
        definition.put("annotations", Map.of(
                "readOnlyHint", true,
                "destructiveHint", false,
                "idempotentHint", true,
                "openWorldHint", false
        ));
        return definition;
    }

    private JMSSession currentSession(String token) {
        var session = SessionManager.getSession(token);
        if (!(session instanceof JMSSession jmsSession)
                || jmsSession.isClosing() || !jmsSession.isActive()) {
            return null;
        }
        return jmsSession;
    }

    private static JsonObject objectValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value.getAsJsonObject();
    }

    private static String stringValue(JsonObject object, String name, int maximum) {
        try {
            JsonElement value = object.get(name);
            String result = value == null || value.isJsonNull() ? "" : value.getAsString();
            return result.length() <= maximum ? result : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String stringValue(JsonObject object, String name) {
        return stringValue(object, name, 128);
    }

    private static int numberValue(JsonObject object, String name) {
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String taskKey(WebSocketSession webSocket, String requestID) {
        return webSocket.getId() + "\u0000" + requestID;
    }

    private static void sendProtocolError(WebSocketSession webSocket, String code, String message) {
        new PacketIO(webSocket).sendPacket("ai_error", Map.of("code", code, "message", message));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        cancelSocketTasks(webSocket);
    }

    @Override
    public void handleTransportError(WebSocketSession webSocket, Throwable exception) {
        log.debug("Chen agent tool websocket closed, websocketId={}", webSocket.getId(), exception);
        cancelSocketTasks(webSocket);
    }

    private void cancelSocketTasks(WebSocketSession webSocket) {
        String prefix = webSocket.getId() + "\u0000";
        for (Map.Entry<String, Future<?>> entry : tasks.entrySet()) {
            if (entry.getKey().startsWith(prefix) && tasks.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel(true);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Future<?> task : tasks.values()) {
            task.cancel(true);
        }
        tasks.clear();
        toolExecutor.shutdownNow();
    }

    private static void closeWebSocket(WebSocketSession webSocket) {
        try {
            if (webSocket.isOpen()) {
                webSocket.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException ignored) {
        }
    }
}
