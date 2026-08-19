package org.jumpserver.chen.web.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.wisp.ServiceGrpc;
import org.jumpserver.wisp.ServiceOuterClass;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private static final Gson GSON = new Gson();
    private static final int MAX_CLIENT_MESSAGE_BYTES = 320 * 1024;
    private static final int MAX_QUESTION_BYTES = 32 * 1024;
    private static final int MAX_TOOL_RESULT_BYTES = 256 * 1024;
    private static final int MAX_SESSION_METADATA_GRANTS = 64;
    private static final long METADATA_APPROVAL_TIMEOUT_SECONDS = 120;

    private final SqlAgentToolService toolService;
    private final Map<String, AgentConnection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor toolExecutor = new ThreadPoolExecutor(
            2,
            4,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "chen-ai-tool");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final ScheduledThreadPoolExecutor approvalExecutor = new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
                Thread thread = new Thread(runnable, "chen-ai-approval");
                thread.setDaemon(true);
                return thread;
            }
    );

    @GrpcClient("wisp")
    private ServiceGrpc.ServiceBlockingStub serviceBlockingStub;

    public AgentWebSocketHandler(SqlAgentToolService toolService) {
        this.toolService = toolService;
        this.toolExecutor.allowCoreThreadTimeOut(true);
        this.approvalExecutor.setRemoveOnCancelPolicy(true);
        this.approvalExecutor.scheduleWithFixedDelay(
                this::expireToolApprovals,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) {
        String token = (String) webSocket.getAttributes().get("token");
        SessionManager.setContext(token);
        if (message.getPayloadLength() > MAX_CLIENT_MESSAGE_BYTES) {
            sendError(webSocket, "invalid_request", "AI request is too large", "");
            return;
        }
        JMSSession session = currentSession(token);
        if (session == null) {
            sendError(webSocket, "session_closed", "The database session is not active", "");
            closeWebSocket(webSocket);
            return;
        }

        Packet packet;
        try {
            packet = GSON.fromJson(message.getPayload(), Packet.class);
        } catch (RuntimeException e) {
            sendError(webSocket, "invalid_request", "Invalid AI request", "");
            return;
        }
        if (packet == null) {
            sendError(webSocket, "invalid_request", "Invalid AI request", "");
            return;
        }
        String type = StringUtils.defaultString(packet.getType());
        if ("ping".equals(type)) {
            new PacketIO(webSocket).sendPacket("pong", Map.of());
            return;
        }
        JsonObject data;
        try {
            data = GSON.toJsonTree(packet.getData()).getAsJsonObject();
        } catch (RuntimeException e) {
            sendError(webSocket, "invalid_request", "Invalid AI request", "");
            return;
        }

        switch (type) {
            case "connect" -> connect(webSocket, token, session, data);
            case "ai_request" -> request(webSocket, token, session, data);
            case "ai_cancel" -> cancel(webSocket, data);
            case "ai_tool_approval" -> approveTool(webSocket, data);
            default -> sendError(webSocket, "invalid_request", "Unsupported AI request", "");
        }
    }

    private void connect(WebSocketSession webSocket, String token, JMSSession session, JsonObject data) {
        if (connections.containsKey(webSocket.getId())) {
            sendError(webSocket, "invalid_request", "AI session is already connected", "");
            return;
        }
        String language = boundedString(data, "language", 32);
        if (StringUtils.isBlank(language)) {
            language = session.getLocale().toLanguageTag();
        }
        AgentConnection connection = new AgentConnection(webSocket, token, session);
        AgentConnection existing = connections.putIfAbsent(webSocket.getId(), connection);
        if (existing != null) {
            sendError(webSocket, "invalid_request", "AI session is already connected", "");
            return;
        }
        try {
            connection.open(language);
        } catch (RuntimeException e) {
            connections.remove(webSocket.getId(), connection);
            log.warn("Open Chen AI stream failed, sessionId={}", session.getJmsSession().getId(), e);
            sendError(webSocket, "ai_unavailable", "AI service is unavailable", "");
        }
    }

    private void request(WebSocketSession webSocket, String token, JMSSession session, JsonObject data) {
        long startedAt = System.nanoTime();
        AgentConnection connection = connections.get(webSocket.getId());
        if (connection == null) {
            sendError(webSocket, "not_connected", "AI session is not connected", "");
            return;
        }
        String requestId = boundedString(data, "id", 128);
        String operation = boundedString(data, "operation", 32).toLowerCase(Locale.ROOT);
        String question = boundedString(data, "question", MAX_QUESTION_BYTES);
        if (requestId.isBlank() || question.isBlank()
                || !SetHolder.OPERATIONS.contains(operation)) {
            sendError(webSocket, "invalid_request", "Invalid AI request", requestId);
            return;
        }
        JsonElement context = data.get("context");
        try {
            SqlAgentToolService.AgentRequestContext resolved = toolService.resolveRequestContext(
                    session, context == null ? "" : GSON.toJson(context), operation
            );
            connection.request(requestId, operation, question, resolved);
            log.info("Chen AI timing request={} stage=submit duration_ms={} outcome=success",
                    requestId, elapsedMilliseconds(startedAt));
        } catch (IllegalArgumentException e) {
            log.info("Chen AI timing request={} stage=submit duration_ms={} outcome=invalid_context",
                    requestId, elapsedMilliseconds(startedAt));
            sendError(webSocket, "invalid_context", e.getMessage(), requestId);
        } catch (RuntimeException e) {
            log.info("Chen AI timing request={} stage=submit duration_ms={} outcome=error",
                    requestId, elapsedMilliseconds(startedAt));
            log.warn("Submit Chen AI request failed, sessionId={}", session.getJmsSession().getId(), e);
            sendError(webSocket, "ai_unavailable", "AI service is unavailable", requestId);
        } finally {
            SessionManager.setContext(token);
        }
    }

    private void cancel(WebSocketSession webSocket, JsonObject data) {
        AgentConnection connection = connections.get(webSocket.getId());
        if (connection == null) {
            return;
        }
        connection.cancel(boundedString(data, "requestId", 128));
    }

    private void approveTool(WebSocketSession webSocket, JsonObject data) {
        AgentConnection connection = connections.get(webSocket.getId());
        if (connection == null) {
            sendError(webSocket, "not_connected", "AI session is not connected", "");
            return;
        }
        connection.resolveToolApproval(
                boundedString(data, "requestId", 128),
                boundedString(data, "approvalId", 128),
                boundedString(data, "decision", 32).toLowerCase(Locale.ROOT)
        );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        AgentConnection connection = connections.remove(webSocket.getId());
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession webSocket, Throwable exception) {
        log.debug("Chen AI websocket transport closed, websocketId={}", webSocket.getId(), exception);
        AgentConnection connection = connections.remove(webSocket.getId());
        if (connection != null) {
            connection.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        for (AgentConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        toolExecutor.shutdownNow();
        approvalExecutor.shutdownNow();
    }

    private JMSSession currentSession(String token) {
        var session = SessionManager.getSession(token);
        if (!(session instanceof JMSSession jmsSession)
                || jmsSession.isClosing() || !jmsSession.isActive()) {
            return null;
        }
        return jmsSession;
    }

    private static String boundedString(JsonObject object, String name, int maximum) {
        try {
            JsonElement value = object.get(name);
            String result = value == null || value.isJsonNull() ? "" : value.getAsString();
            return result.length() <= maximum ? result : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void sendError(WebSocketSession webSocket, String code, String message, String requestId) {
        new PacketIO(webSocket).sendPacket("ai_error", Map.of(
                "code", code,
                "message", StringUtils.defaultString(message),
                "requestId", StringUtils.defaultString(requestId)
        ));
    }

    private static void closeWebSocket(WebSocketSession webSocket) {
        try {
            if (webSocket.isOpen()) {
                webSocket.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException ignored) {
        }
    }

    private void expireToolApprovals() {
        long now = System.nanoTime();
        for (AgentConnection connection : this.connections.values()) {
            try {
                connection.expireToolApprovalIfDue(now);
            } catch (RuntimeException e) {
                log.debug("Expire Chen AI metadata approval failed, websocketId={}",
                        connection.webSocket.getId(), e);
            }
        }
    }

    private final class AgentConnection {
        private final WebSocketSession webSocket;
        private final String token;
        private final JMSSession databaseSession;
        private final AtomicReference<ActiveRequest> activeRequest = new AtomicReference<>();
        private final AtomicReference<PendingToolApproval> pendingToolApproval = new AtomicReference<>();
        private final Deque<SqlAgentToolService.MetadataApprovalScope> sessionMetadataGrants = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile StreamObserver<ServiceOuterClass.AgentClientEvent> requestObserver;
        private volatile boolean enabled;
        private volatile String provider = "";
        private volatile String model = "";

        private AgentConnection(WebSocketSession webSocket, String token, JMSSession databaseSession) {
            this.webSocket = webSocket;
            this.token = token;
            this.databaseSession = databaseSession;
        }

        private void open(String language) {
            this.requestObserver = ServiceGrpc.newStub(serviceBlockingStub.getChannel())
                    .agentSession(new StreamObserver<>() {
                        @Override
                        public void onNext(ServiceOuterClass.AgentServerEvent event) {
                            onServerEvent(event);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            if (!closed.get()) {
                                log.warn("Chen AI stream failed, sessionId={}",
                                        databaseSession.getJmsSession().getId(), throwable);
                                sendError(webSocket, "ai_unavailable", "AI service is unavailable", "");
                            }
                            enabled = false;
                            clearPendingApproval();
                            ActiveRequest active = activeRequest.getAndSet(null);
                            logRequestTiming(active, "stream_error");
                        }

                        @Override
                        public void onCompleted() {
                            enabled = false;
                            clearPendingApproval();
                            ActiveRequest active = activeRequest.getAndSet(null);
                            logRequestTiming(active, "stream_closed");
                        }
                    });
            var identity = this.databaseSession.getJmsSession();
            var open = ServiceOuterClass.AgentSessionOpen.newBuilder()
                    .setSessionId(identity.getId())
                    .setUserId(identity.getUserId())
                    .setOrganizationId(identity.getOrgId())
                    .setAssetId(identity.getAssetId())
                    .setAccountId(identity.getAccountId())
                    .setProtocol(identity.getProtocol())
                    .setLanguage(language)
                    .setSurface("sql")
                    .build();
            send(ServiceOuterClass.AgentClientEvent.newBuilder().setOpen(open).build());
        }

        private void request(
                String requestId,
                String operation,
                String question,
                SqlAgentToolService.AgentRequestContext context
        ) {
            if (!this.enabled) {
                sendError(this.webSocket, "ai_unavailable", "AI service is not ready", requestId);
                return;
            }
            ActiveRequest active = new ActiveRequest(
                    requestId, context, System.nanoTime(), new ConcurrentHashMap<>(),
                    new ConcurrentLinkedDeque<>()
            );
            if (!this.activeRequest.compareAndSet(null, active)) {
                sendError(this.webSocket, "request_active", "Another AI request is active", requestId);
                return;
            }
            var request = ServiceOuterClass.AgentRequest.newBuilder()
                    .setId(requestId)
                    .setOperation(operation)
                    .setQuestion(question)
                    .setContextJson(context.sanitizedJson())
                    .build();
            try {
                send(ServiceOuterClass.AgentClientEvent.newBuilder().setRequest(request).build());
            } catch (RuntimeException e) {
                this.activeRequest.compareAndSet(active, null);
                throw e;
            }
        }

        private void cancel(String requestId) {
            ActiveRequest active = this.activeRequest.get();
            if (active == null || (StringUtils.isNotBlank(requestId) && !active.id().equals(requestId))) {
                return;
            }
            rejectPendingApproval(active, "cancelled", "Database metadata approval was cancelled");
            var cancel = ServiceOuterClass.AgentCancel.newBuilder().setRequestId(active.id()).build();
            send(ServiceOuterClass.AgentClientEvent.newBuilder().setCancel(cancel).build());
        }

        private void onServerEvent(ServiceOuterClass.AgentServerEvent event) {
            switch (event.getEventCase()) {
                case READY -> onReady(event.getReady());
                case CHAT -> onChat(event.getChat());
                case TOOL_CALL -> onToolCall(event.getToolCall());
                case ERROR -> onAgentError(event.getError());
                case EVENT_NOT_SET -> sendError(this.webSocket, "protocol_error", "Invalid AI response", "");
            }
        }

        private void onReady(ServiceOuterClass.AgentReady ready) {
            this.enabled = ready.getEnabled();
            this.provider = ready.getProvider();
            this.model = ready.getModel();
            new PacketIO(this.webSocket).sendPacket("ai_ready", Map.of(
                    "enabled", ready.getEnabled(),
                    "reason", ready.getReason(),
                    "sessionId", ready.getSessionId(),
                    "surface", ready.getSurface(),
                    "provider", ready.getProvider(),
                    "model", ready.getModel()
            ));
        }

        private void onChat(ServiceOuterClass.AgentChatMessage chat) {
            try {
                JsonObject message = JsonParser.parseString(chat.getMessageJson()).getAsJsonObject();
                new PacketIO(this.webSocket).sendPacket("ai_chat", message);
                String completedRequestId = idleRequestId(message);
                if (StringUtils.isNotBlank(completedRequestId)) {
                    ActiveRequest active = this.activeRequest.get();
                    if (active != null && active.id().equals(completedRequestId)) {
                        if (this.activeRequest.compareAndSet(active, null)) {
                            rejectPendingApproval(active, "cancelled", "Database metadata approval was cancelled");
                            logRequestTiming(active, "complete");
                        }
                    }
                }
            } catch (RuntimeException e) {
                sendError(this.webSocket, "protocol_error", "Invalid AI response", "");
            }
        }

        private void onToolCall(ServiceOuterClass.AgentToolCall call) {
            ActiveRequest active = this.activeRequest.get();
            if (active == null) {
                sendToolResult(call.getId(), "", "No active SQL editor context");
                return;
            }
            String cacheKey = call.getName() + "\u0000" + call.getArgumentsJson();
            String cachedResult = active.toolResults().get(cacheKey);
            if (cachedResult != null) {
                log.info("Chen AI timing request={} stage=tool tool={} queue_ms=0 duration_ms=0 outcome=cache_hit",
                        active.id(), call.getName());
                sendToolResult(call.getId(), cachedResult, "");
                return;
            }
            long queuedAt = System.nanoTime();
            if ("inspect_schema".equalsIgnoreCase(call.getName())) {
                SqlAgentToolService.MetadataApprovalScope scope;
                try {
                    scope = toolService.resolveMetadataApprovalScope(active.context(), call.getArgumentsJson());
                } catch (IllegalArgumentException e) {
                    sendToolResult(call.getId(), "", "Invalid database metadata request");
                    return;
                }
                if (!isMetadataApproved(active, scope)) {
                    requestToolApproval(active, call, cacheKey, scope, queuedAt);
                    return;
                }
            }
            executeTool(active, call, cacheKey, queuedAt);
        }

        private void requestToolApproval(
                ActiveRequest active,
                ServiceOuterClass.AgentToolCall call,
                String cacheKey,
                SqlAgentToolService.MetadataApprovalScope scope,
                long queuedAt
        ) {
            String approvalId = UUID.randomUUID().toString();
            var pending = new PendingToolApproval(approvalId, active, call, cacheKey, scope, queuedAt);
            if (!this.pendingToolApproval.compareAndSet(null, pending)) {
                sendToolResult(call.getId(), "", "Another database metadata approval is pending");
                return;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("approvalId", approvalId);
            event.put("requestId", active.id());
            event.put("toolCallId", call.getId());
            event.put("tool", call.getName());
            event.put("provider", this.provider);
            event.put("model", this.model);
            event.put("database", scope.database());
            event.put("schema", scope.schema());
            event.put("tables", scope.tables());
            event.put("query", scope.query());
            event.put("discovery", scope.discovery());
            event.put("maxMatches", scope.discovery()
                    ? SqlAgentToolService.MAX_DISCOVER_TABLES
                    : SqlAgentToolService.MAX_INSPECT_TABLES);
            event.put("followUpTableLimit", SqlAgentToolService.MAX_INSPECT_TABLES);
            event.put("dataCategories", SqlAgentToolService.INSPECT_SCHEMA_DATA_CATEGORIES);
            event.put("expandedScope", hasMetadataGrantForContext(active, scope));
            event.put("expiresInSeconds", METADATA_APPROVAL_TIMEOUT_SECONDS);
            log.info(
                    "Chen AI metadata approval request={} stage=request table_count={} search={} expanded={} provider={} model={}",
                    active.id(), scope.tables().size(), StringUtils.isNotBlank(scope.query()),
                    event.get("expandedScope"), this.provider, this.model
            );
            new PacketIO(this.webSocket).sendPacket("ai_tool_approval_required", event);
        }

        private void resolveToolApproval(String requestId, String approvalId, String decision) {
            PendingToolApproval pending = this.pendingToolApproval.get();
            ActiveRequest active = this.activeRequest.get();
            if (pending == null || active == null
                    || !pending.approvalId.equals(approvalId)
                    || !pending.active.id().equals(requestId)
                    || pending.active != active) {
                sendError(this.webSocket, "invalid_approval", "Metadata approval is no longer valid", requestId);
                return;
            }
            if (!SetHolder.APPROVAL_DECISIONS.contains(decision)) {
                sendError(this.webSocket, "invalid_approval", "Invalid metadata approval decision", requestId);
                return;
            }
            if (!this.pendingToolApproval.compareAndSet(pending, null)) {
                sendError(this.webSocket, "invalid_approval", "Metadata approval is no longer valid", requestId);
                return;
            }
            log.info(
                    "Chen AI metadata approval request={} stage=decision outcome={} table_count={} search={}",
                    requestId, decision, pending.scope.tables().size(),
                    StringUtils.isNotBlank(pending.scope.query())
            );
            if ("reject".equals(decision)) {
                sendToolResult(pending.call.getId(), "", "User denied database metadata access");
                sendApprovalResolved(pending, "rejected");
                return;
            }
            if ("approve_session".equals(decision)) {
                addSessionMetadataGrant(pending.scope);
            } else {
                addRequestMetadataGrant(pending.active, pending.scope);
            }
            sendApprovalResolved(pending, "approved");
            executeTool(pending.active, pending.call, pending.cacheKey, pending.queuedAt);
        }

        private void expireToolApprovalIfDue(long now) {
            PendingToolApproval pending = this.pendingToolApproval.get();
            if (pending == null || now < pending.expiresAt) {
                return;
            }
            if (!this.pendingToolApproval.compareAndSet(pending, null)) {
                return;
            }
            log.info(
                    "Chen AI metadata approval request={} stage=decision outcome=expired table_count={} search={}",
                    pending.active.id(), pending.scope.tables().size(),
                    StringUtils.isNotBlank(pending.scope.query())
            );
            sendToolResult(pending.call.getId(), "", "Database metadata approval expired");
            sendApprovalResolved(pending, "expired");
        }

        private void rejectPendingApproval(ActiveRequest active, String outcome, String error) {
            PendingToolApproval pending = this.pendingToolApproval.get();
            if (pending == null || pending.active != active
                    || !this.pendingToolApproval.compareAndSet(pending, null)) {
                return;
            }
            sendToolResult(pending.call.getId(), "", error);
            sendApprovalResolved(pending, outcome);
        }

        private void clearPendingApproval() {
            this.pendingToolApproval.set(null);
        }

        private void sendApprovalResolved(PendingToolApproval pending, String outcome) {
            new PacketIO(this.webSocket).sendPacket("ai_tool_approval_resolved", Map.of(
                    "approvalId", pending.approvalId,
                    "requestId", pending.active.id(),
                    "outcome", outcome
            ));
        }

        private boolean isMetadataApproved(
                ActiveRequest active,
                SqlAgentToolService.MetadataApprovalScope requested
        ) {
            if (active.metadataGrants().stream().anyMatch(grant -> grant.covers(requested))) {
                return true;
            }
            synchronized (this) {
                return this.sessionMetadataGrants.stream().anyMatch(grant -> grant.covers(requested));
            }
        }

        private boolean hasMetadataGrantForContext(
                ActiveRequest active,
                SqlAgentToolService.MetadataApprovalScope requested
        ) {
            if (active.metadataGrants().stream().anyMatch(grant -> sameMetadataContext(grant, requested))) {
                return true;
            }
            synchronized (this) {
                return this.sessionMetadataGrants.stream().anyMatch(grant ->
                        sameMetadataContext(grant, requested));
            }
        }

        private boolean sameMetadataContext(
                SqlAgentToolService.MetadataApprovalScope grant,
                SqlAgentToolService.MetadataApprovalScope requested
        ) {
            return grant.database().equalsIgnoreCase(requested.database())
                    && grant.schema().equalsIgnoreCase(requested.schema())
                    && grant.nodeKey().equals(requested.nodeKey());
        }

        private void addRequestMetadataGrant(
                ActiveRequest active,
                SqlAgentToolService.MetadataApprovalScope scope
        ) {
            if (active.metadataGrants().stream().noneMatch(grant -> grant.covers(scope))) {
                active.metadataGrants().addLast(scope);
            }
        }

        private synchronized void addSessionMetadataGrant(SqlAgentToolService.MetadataApprovalScope scope) {
            if (this.sessionMetadataGrants.stream().anyMatch(grant -> grant.covers(scope))) {
                return;
            }
            while (this.sessionMetadataGrants.size() >= MAX_SESSION_METADATA_GRANTS) {
                this.sessionMetadataGrants.removeFirst();
            }
            this.sessionMetadataGrants.addLast(scope);
        }

        private void executeTool(
                ActiveRequest active,
                ServiceOuterClass.AgentToolCall call,
                String cacheKey,
                long queuedAt
        ) {
            try {
                toolExecutor.execute(() -> {
                    long startedAt = System.nanoTime();
                    String outcome = "success";
                    SessionManager.setContext(this.token);
                    try {
                        String result = toolService.execute(
                                this.databaseSession,
                                active.context(),
                                call.getName(),
                                call.getArgumentsJson()
                        );
                        if (result.length() > MAX_TOOL_RESULT_BYTES) {
                            outcome = "result_too_large";
                            sendToolResult(call.getId(), "", "Database metadata result is too large");
                            return;
                        }
                        active.toolResults().put(cacheKey, result);
                        sendToolResult(call.getId(), result, "");
                    } catch (IllegalArgumentException e) {
                        outcome = "invalid_request";
                        sendToolResult(call.getId(), "", "Invalid database metadata request");
                    } catch (SQLException e) {
                        outcome = "sql_error";
                        log.warn("Chen AI metadata query failed, sessionId={}, tool={}",
                                this.databaseSession.getJmsSession().getId(), call.getName(), e);
                        sendToolResult(call.getId(), "", "Database metadata request failed");
                    } catch (RuntimeException e) {
                        outcome = "error";
                        log.warn("Chen AI tool failed, sessionId={}, tool={}",
                                this.databaseSession.getJmsSession().getId(), call.getName(), e);
                        sendToolResult(call.getId(), "", "Database metadata request failed");
                    } finally {
                        log.info("Chen AI timing request={} stage=tool tool={} queue_ms={} duration_ms={} outcome={}",
                                active.id(), call.getName(), elapsedMilliseconds(queuedAt, startedAt),
                                elapsedMilliseconds(startedAt), outcome);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.info("Chen AI timing request={} stage=tool tool={} queue_ms={} duration_ms=0 outcome=rejected",
                        active.id(), call.getName(), elapsedMilliseconds(queuedAt));
                sendToolResult(call.getId(), "", "Database metadata queue is full");
            }
        }

        private void onAgentError(ServiceOuterClass.AgentError error) {
            sendError(this.webSocket, error.getCode(), error.getMessage(), error.getRequestId());
            ActiveRequest active = this.activeRequest.get();
            if (active != null && (error.getRequestId().isBlank() || active.id().equals(error.getRequestId()))) {
                if (this.activeRequest.compareAndSet(active, null)) {
                    rejectPendingApproval(active, "cancelled", "Database metadata approval was cancelled");
                    logRequestTiming(active, "error");
                }
            }
        }

        private void sendToolResult(String id, String result, String error) {
            var toolResult = ServiceOuterClass.AgentToolResult.newBuilder()
                    .setId(id)
                    .setResultJson(result)
                    .setError(error)
                    .build();
            send(ServiceOuterClass.AgentClientEvent.newBuilder().setToolResult(toolResult).build());
        }

        private synchronized void send(ServiceOuterClass.AgentClientEvent event) {
            if (this.closed.get() || this.requestObserver == null) {
                throw new IllegalStateException("AI stream is closed");
            }
            this.requestObserver.onNext(event);
        }

        private void close() {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            this.enabled = false;
            clearPendingApproval();
            ActiveRequest active = this.activeRequest.getAndSet(null);
            logRequestTiming(active, "closed");
            StreamObserver<ServiceOuterClass.AgentClientEvent> observer = this.requestObserver;
            if (observer == null) {
                return;
            }
            try {
                observer.onNext(ServiceOuterClass.AgentClientEvent.newBuilder()
                        .setClose(ServiceOuterClass.Empty.getDefaultInstance()).build());
            } catch (RuntimeException ignored) {
            }
            try {
                observer.onCompleted();
            } catch (RuntimeException ignored) {
            }
        }

        private void logRequestTiming(ActiveRequest active, String outcome) {
            if (active == null) {
                return;
            }
            log.info("Chen AI timing request={} stage=request duration_ms={} outcome={}",
                    active.id(), elapsedMilliseconds(active.startedAt()), outcome);
        }
    }

    private static String idleRequestId(JsonObject message) {
        try {
            String requestId = message.getAsJsonObject("metadata").get("requestId").getAsString();
            for (JsonElement value : message.getAsJsonArray("parts")) {
                JsonObject part = value.getAsJsonObject();
                if ("data-progress".equals(part.get("type").getAsString())
                        && "idle".equals(part.getAsJsonObject("data").get("state").getAsString())) {
                    return requestId;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static long elapsedMilliseconds(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedAt));
    }

    private static long elapsedMilliseconds(long startedAt, long endedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, endedAt - startedAt));
    }

    private record ActiveRequest(
            String id,
            SqlAgentToolService.AgentRequestContext context,
            long startedAt,
            Map<String, String> toolResults,
            Deque<SqlAgentToolService.MetadataApprovalScope> metadataGrants
    ) {
    }

    private static final class PendingToolApproval {
        private final String approvalId;
        private final ActiveRequest active;
        private final ServiceOuterClass.AgentToolCall call;
        private final String cacheKey;
        private final SqlAgentToolService.MetadataApprovalScope scope;
        private final long queuedAt;
        private final long expiresAt;

        private PendingToolApproval(
                String approvalId,
                ActiveRequest active,
                ServiceOuterClass.AgentToolCall call,
                String cacheKey,
                SqlAgentToolService.MetadataApprovalScope scope,
                long queuedAt
        ) {
            this.approvalId = approvalId;
            this.active = active;
            this.call = call;
            this.cacheKey = cacheKey;
            this.scope = scope;
            this.queuedAt = queuedAt;
            this.expiresAt = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(METADATA_APPROVAL_TIMEOUT_SECONDS);
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<String> OPERATIONS =
                java.util.Set.of("generate", "explain", "repair");
        private static final java.util.Set<String> APPROVAL_DECISIONS =
                java.util.Set.of("approve_once", "approve_session", "reject");
    }
}
