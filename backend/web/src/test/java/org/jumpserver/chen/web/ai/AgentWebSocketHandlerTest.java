package org.jumpserver.chen.web.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.wisp.Common;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWebSocketHandlerTest {
    @Test
    void marksSqlProposalAsFinalResult() {
        Map<String, Object> proposal = AgentWebSocketHandler.toolDefinitions().stream()
                .filter(tool -> "propose_sql".equals(tool.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(true, ((Map<?, ?>) proposal.get("_meta")).get("com.jumpserver/finalResult"));
    }

    @Test
    void deadlineSuppressesLateDriverResult() throws Exception {
        assertSingleOutcomeAfterInterruption(true);
    }

    @Test
    void cancellationSuppressesLateDriverResult() throws Exception {
        assertSingleOutcomeAfterInterruption(false);
    }

    private void assertSingleOutcomeAfterInterruption(boolean timeout) throws Exception {
        var fixture = new Fixture(timeout ? Duration.ofSeconds(1) : Duration.ofSeconds(60));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(fixture.service.execute(eq(fixture.session), isNull(), anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            // Simulate JDBC returning successfully after ignoring cancellation.
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(3, TimeUnit.SECONDS);
                    if (!released) throw new SQLException("Test driver was not released");
                } catch (InterruptedException ignored) {
                }
            }
            return "{}";
        });
        try {
            fixture.submit();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            if (!timeout) {
                fixture.handler.handleToolCancel(fixture.socket, fixture.session, packet("""
                        {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"request-1"}}
                        """));
            }
            assertOutcome(fixture.responses.poll(3, TimeUnit.SECONDS), timeout ? "timeout" : "cancelled");
            if (!timeout) assertEquals("mcp.cancel_result", fixture.responses.poll(3, TimeUnit.SECONDS).get("type").getAsString());
        } finally {
            release.countDown();
            fixture.close();
        }
        assertTrue(fixture.responses.isEmpty(), "Late JDBC completion must not produce a second response");
    }

    @Test
    void deadlineAlsoExpiresQueuedRequests() throws Exception {
        var fixture = new Fixture(Duration.ofSeconds(1));
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(fixture.handler, "toolExecutor");
        assertNotNull(executor);
        try {
            for (int i = 0; i < 2; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            fixture.submit();
            assertOutcome(fixture.responses.poll(3, TimeUnit.SECONDS), "timeout");
            verifyNoInteractions(fixture.service);
            assertTrue(executor.getQueue().isEmpty());
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    @Test
    void wrappedSqlTimeoutIsAnExpectedToolOutcome() throws Exception {
        var fixture = new Fixture(Duration.ofSeconds(60));
        when(fixture.service.execute(eq(fixture.session), isNull(), anyString(), anyString()))
                .thenThrow(new SQLException("Driver failed", new SQLTimeoutException("Query timed out")));
        try {
            fixture.submit();
            assertOutcome(fixture.responses.poll(3, TimeUnit.SECONDS), "timeout");
        } finally {
            fixture.close();
        }
    }

    @Test
    void ordinaryDatabaseFailureRemainsAnError() throws Exception {
        var fixture = new Fixture(Duration.ofSeconds(60));
        when(fixture.service.execute(eq(fixture.session), isNull(), anyString(), anyString()))
                .thenThrow(new SQLException("Connection failed"));
        try {
            fixture.submit();
            var response = fixture.responses.poll(3, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(-32603, response.getAsJsonObject("data").getAsJsonObject("error").get("code").getAsInt());
        } finally {
            fixture.close();
        }
    }

    private static void assertOutcome(JsonObject response, String status) {
        assertNotNull(response);
        var data = response.getAsJsonObject("data");
        assertFalse(data.has("error"));
        var result = data.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        var metadata = result.getAsJsonObject("_meta").getAsJsonObject("com.jumpserver/agent");
        assertEquals(status, metadata.get("status").getAsString());
        assertEquals("tool_" + status, metadata.get("code").getAsString());
    }

    private static JsonObject packet(String data) {
        var packet = JsonParser.parseString("{\"version\":1,\"resource_session_id\":\"session-1\"}").getAsJsonObject();
        packet.add("data", JsonParser.parseString(data));
        return packet;
    }

    private static class Fixture {
        final SqlAgentToolService service = mock(SqlAgentToolService.class);
        final JMSSession session = mock(JMSSession.class);
        final WebSocketSession socket = mock(WebSocketSession.class);
        final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>();
        final AgentWebSocketHandler handler;

        Fixture(Duration timeout) throws Exception {
            handler = new AgentWebSocketHandler(service, timeout);
            when(session.getJmsSession()).thenReturn(Common.Session.newBuilder().setId("session-1").build());
            when(socket.getId()).thenReturn("socket-1");
            when(socket.isOpen()).thenReturn(true);
            doAnswer(call -> {
                responses.add(JsonParser.parseString(((TextMessage) call.getArgument(0)).getPayload()).getAsJsonObject());
                return null;
            }).when(socket).sendMessage(any(TextMessage.class));
        }

        void submit() {
            handler.handleToolRequest(socket, "token-1", session, packet("""
                    {"jsonrpc":"2.0","id":"request-1","method":"tools/call","params":{
                      "name":"inspect_schema","arguments":{},"_meta":{
                        "com.jumpserver/agent":{"resource_session_id":"session-1","revision":1,"tool_call_id":"tool-1"},
                        "com.jumpserver/sqlContext":{},"com.jumpserver/sqlOperation":"generate"
                      }
                    }}
                    """));
        }

        void close() throws InterruptedException {
            handler.shutdown();
            var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(handler, "toolExecutor");
            assertNotNull(executor);
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
