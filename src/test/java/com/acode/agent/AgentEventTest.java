package com.acode.agent;

import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamTextRecordCarriesText() {
        StreamText event = new StreamText("你好");
        assertEquals("你好", event.text());
    }

    @Test
    void toolUseEventRecordCarriesIdNameAndArgs() {
        var args = JSON.createObjectNode().put("file_path", "a.txt");
        ToolUseEvent event = new ToolUseEvent("toolu_1", "ReadFile", args);
        assertEquals("toolu_1", event.toolId());
        assertEquals("ReadFile", event.toolName());
        assertEquals("a.txt", event.args().path("file_path").asText());
    }

    @Test
    void toolResultEventRecordCarriesOutputAndErrorFlag() {
        ToolResultEvent ok = new ToolResultEvent("toolu_1", "ReadFile", "内容", false);
        assertFalse(ok.isError());
        assertEquals("内容", ok.output());

        ToolResultEvent fail = new ToolResultEvent("toolu_2", "Bash", "命令失败", true);
        assertTrue(fail.isError());
    }

    @Test
    void turnCompleteRecordCarriesTurnNumber() {
        assertEquals(3, new TurnComplete(3).turn());
    }

    @Test
    void loopCompleteRecordCarriesTotalTurns() {
        assertEquals(2, new LoopComplete(2).totalTurns());
    }

    @Test
    void errorEventRecordCarriesMessage() {
        assertEquals("连接失败", new ErrorEvent("连接失败").message());
    }

    @Test
    void retryEventRecordCarriesReasonAndWait() {
        RetryEvent event = new RetryEvent("限流", 2000);
        assertEquals("限流", event.reason());
        assertEquals(2000, event.waitMs());
    }

    @Test
    void allSevenEventTypesAreSealedMembers() {
        // sealed interface 的编译期约束：这 7 种 record 都能被 instanceof 判别
        AgentEvent e = new StreamText("x");
        assertTrue(e instanceof StreamText);
        assertTrue(new ToolUseEvent("id", "n", JSON.createObjectNode()) instanceof AgentEvent);
        assertTrue(new ToolResultEvent("id", "n", "out", false) instanceof AgentEvent);
        assertTrue(new TurnComplete(1) instanceof AgentEvent);
        assertTrue(new LoopComplete(1) instanceof AgentEvent);
        assertTrue(new ErrorEvent("e") instanceof AgentEvent);
        assertTrue(new RetryEvent("r", 0) instanceof AgentEvent);
    }

    @Test
    void queueCapacityIs64() {
        assertEquals(64, AgentEvent.QUEUE_CAPACITY);
    }
}
