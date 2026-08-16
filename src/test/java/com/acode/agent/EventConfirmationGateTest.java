package com.acode.agent;

import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventConfirmationGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static ToolUseBlock call(String name, JsonNode input) {
        return new ToolUseBlock("toolu_1", name, input);
    }

    @Test
    void confirmEmitsEventThenReturnsTrueWhenApproved() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        JsonNode args = JSON.createObjectNode().put("file_path", "a.txt");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ConfirmationRequestEvent[] captured = new ConfirmationRequestEvent[1];
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                captured[0] = (ConfirmationRequestEvent) events.take();
                captured[0].response().answer(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        EventConfirmationGate gate = new EventConfirmationGate();
        assertTrue(gate.confirm(call("WriteFile", args), events, cancelled));
        responder.join(1000);

        assertEquals("toolu_1", captured[0].toolId());
        assertEquals("WriteFile", captured[0].toolName());
        assertEquals(args.toString(), captured[0].argsSummary());
    }

    @Test
    void confirmReturnsFalseWhenRejected() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                ((ConfirmationRequestEvent) events.take()).response().answer(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        EventConfirmationGate gate = new EventConfirmationGate();
        assertFalse(gate.confirm(call("Bash", JSON.createObjectNode()), events, cancelled));
        responder.join(1000);
    }

    @Test
    void confirmReturnsFalseWhenCancelled() {
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        EventConfirmationGate gate = new EventConfirmationGate();
        assertFalse(gate.confirm(call("WriteFile", JSON.createObjectNode()), events, cancelled));
    }

    @Test
    void summarizeTruncatesLongJson() {
        String longValue = "x".repeat(200);
        JsonNode input = JSON.createObjectNode().put("content", longValue);
        String summary = EventConfirmationGate.summarize(input);
        assertEquals(EventConfirmationGate.SUMMARY_MAX_CHARS + 1, summary.length());
        assertTrue(summary.endsWith("…"));
        assertFalse(summary.contains(longValue));
    }

    @Test
    void summarizeReturnsEmptyForNull() {
        assertEquals("", EventConfirmationGate.summarize(null));
        assertEquals("", EventConfirmationGate.summarize(JSON.nullNode()));
    }
}
