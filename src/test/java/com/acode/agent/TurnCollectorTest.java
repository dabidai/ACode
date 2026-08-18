package com.acode.agent;

import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.UsageEvent;
import com.acode.provider.FakeProvider;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.NetworkException;
import com.acode.provider.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCollectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static List<AgentEvent> drain(BlockingQueue<AgentEvent> queue) {
        List<AgentEvent> list = new ArrayList<>();
        queue.drainTo(list);
        return list;
    }

    @Test
    void singleTurnScriptAccumulatesTextToolsAndStopReason() {
        BlockingQueue<AgentEvent> events = queue();
        TurnCollector collector = new TurnCollector(events, new AtomicBoolean(false));
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.delta("你好"),
                FakeProvider.delta("世界"),
                FakeProvider.toolUse("toolu_1", "ReadFile",
                        JSON.createObjectNode().put("file_path", "a.txt")),
                FakeProvider.complete("end_turn"))));
        provider.streamChat(request(), collector);

        assertEquals("你好世界", collector.text());
        assertEquals(1, collector.toolUses().size());
        assertEquals("ReadFile", collector.toolUses().get(0).name());
        assertEquals("end_turn", collector.stopReason());
        assertNull(collector.error());
    }

    @Test
    void eventsQueuedInScriptOrderWithStreamTextBeforeToolUse() {
        BlockingQueue<AgentEvent> events = queue();
        TurnCollector collector = new TurnCollector(events, new AtomicBoolean(false));
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.delta("先"),
                FakeProvider.toolUse("toolu_1", "Glob",
                        JSON.createObjectNode().put("pattern", "**/*.java")),
                FakeProvider.delta("后"),
                FakeProvider.complete())));
        provider.streamChat(request(), collector);

        List<AgentEvent> list = drain(events);
        assertEquals(3, list.size());
        assertInstanceOf(StreamText.class, list.get(0));
        assertEquals("先", ((StreamText) list.get(0)).text());
        assertInstanceOf(ToolUseEvent.class, list.get(1));
        assertEquals("Glob", ((ToolUseEvent) list.get(1)).toolName());
        assertInstanceOf(StreamText.class, list.get(2));
        assertEquals("后", ((StreamText) list.get(2)).text());
    }

    @Test
    void usageForwardedAsUsageEvent() {
        BlockingQueue<AgentEvent> events = queue();
        TurnCollector collector = new TurnCollector(events, new AtomicBoolean(false));
        Usage usage = new Usage(100, 1, 80, 20);
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.usage(usage),
                FakeProvider.complete())));
        provider.streamChat(request(), collector);

        assertEquals(usage, collector.usage());
        List<AgentEvent> list = drain(events);
        assertEquals(1, list.size());
        UsageEvent event = assertInstanceOf(UsageEvent.class, list.get(0));
        assertEquals(usage, event.usage());
    }

    @Test
    void callbacksIgnoredAfterCancelled() {
        BlockingQueue<AgentEvent> events = queue();
        TurnCollector collector = new TurnCollector(events, new AtomicBoolean(true));
        collector.onDelta("不该累积");
        collector.onToolUse(new com.acode.provider.ToolUseBlock("id", "Bash",
                JSON.createObjectNode().put("command", "x")));
        collector.onComplete("end_turn");
        collector.onError(new InvalidRequestException("错"));
        collector.onUsage(new Usage(1, 1, 0, 0));

        assertEquals("", collector.text());
        assertTrue(collector.toolUses().isEmpty());
        assertNull(collector.stopReason());
        assertNull(collector.error());
        assertNull(collector.usage());
        assertTrue(drain(events).isEmpty(), "取消后不产生事件");
    }

    @Test
    void errorIsRecordedForUpperLayer() {
        BlockingQueue<AgentEvent> events = queue();
        TurnCollector collector = new TurnCollector(events, new AtomicBoolean(false));
        FakeProvider provider = FakeProvider.failing(new NetworkException("网络中断"));
        provider.streamChat(request(), collector);

        assertInstanceOf(NetworkException.class, collector.error());
        assertEquals("", collector.text());
    }

    private static com.acode.provider.ChatRequest request() {
        return com.acode.provider.ChatRequest.builder()
                .model("test-model")
                .message(com.acode.provider.ChatMessage.of(com.acode.provider.ChatMessage.Role.USER, "hi"))
                .build();
    }
}
