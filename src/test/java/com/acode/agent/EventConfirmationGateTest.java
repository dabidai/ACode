package com.acode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.permission.PermissionResponse;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EventConfirmationGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static ToolUseBlock call(String name, JsonNode input) {
        return new ToolUseBlock("toolu_1", name, input);
    }

    @Test
    void confirmEmitsEventThenReturnsAllowWhenApproved() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        JsonNode args = JSON.createObjectNode().put("file_path", "a.txt");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ConfirmationRequestEvent[] captured = new ConfirmationRequestEvent[1];
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                captured[0] = (ConfirmationRequestEvent) events.take();
                captured[0].response().answer(PermissionResponse.ALLOW);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        EventConfirmationGate gate = new EventConfirmationGate();
        assertEquals(PermissionResponse.ALLOW, gate.confirm(call("WriteFile", args), events, cancelled));
        responder.join(1000);

        assertEquals("toolu_1", captured[0].toolId());
        assertEquals("WriteFile", captured[0].toolName());
        assertEquals(args.toString(), captured[0].argsSummary());
    }

    @Test
    void confirmReturnsDenyWhenRejected() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                ((ConfirmationRequestEvent) events.take()).response().answer(PermissionResponse.DENY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        EventConfirmationGate gate = new EventConfirmationGate();
        assertEquals(PermissionResponse.DENY, gate.confirm(call("Bash", JSON.createObjectNode()), events, cancelled));
        responder.join(1000);
    }

    @Test
    void confirmReturnsAllowAlwaysWhenChosen() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                ((ConfirmationRequestEvent) events.take()).response().answer(PermissionResponse.ALLOW_ALWAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        EventConfirmationGate gate = new EventConfirmationGate();
        assertEquals(PermissionResponse.ALLOW_ALWAYS,
                gate.confirm(call("WriteFile", JSON.createObjectNode()), events, cancelled));
        responder.join(1000);
    }

    @Test
    void confirmReturnsDenyWhenCancelled() {
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        EventConfirmationGate gate = new EventConfirmationGate();
        assertEquals(PermissionResponse.DENY,
                gate.confirm(call("WriteFile", JSON.createObjectNode()), events, cancelled));
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
