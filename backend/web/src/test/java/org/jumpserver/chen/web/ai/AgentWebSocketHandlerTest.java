package org.jumpserver.chen.web.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentWebSocketHandlerTest {
    @Test
    void marksSqlProposalAsFinalResult() {
        Map<String, Object> proposal = AgentWebSocketHandler.toolDefinitions().stream()
                .filter(tool -> "propose_sql".equals(tool.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(true, ((Map<?, ?>) proposal.get("_meta")).get("com.jumpserver/finalResult"));
    }
}
