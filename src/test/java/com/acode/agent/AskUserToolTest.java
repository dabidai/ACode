package com.acode.agent;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Permission;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskUserToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static ToolUseBlock call(String... options) {
        ArrayNode arr = JSON.createArrayNode();
        for (String option : options) {
            arr.add(option);
        }
        return new ToolUseBlock("toolu_1", "AskUser",
                JSON.createObjectNode().put("question", "你想先做哪个？").set("options", arr));
    }

    @Test
    void userSelectionPassedBackAsSuccess() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        ChoiceRequestEvent[] captured = new ChoiceRequestEvent[1];
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                captured[0] = (ChoiceRequestEvent) events.take();
                captured[0].response().answer("B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AskUserTool tool = new AskUserTool();
        ToolResult result = tool.executeInteractive(call("A", "B"), events, new AtomicBoolean(false));
        responder.join(1000);

        assertFalse(result.isError(), "选中项应作为成功结果回传");
        assertEquals("B", result.content());
        assertEquals("你想先做哪个？", captured[0].question());
        assertEquals(List.of("A", "B"), captured[0].options());
    }

    @Test
    void cancelReturnsFailureWithCancelReason() throws Exception {
        BlockingQueue<AgentEvent> events = queue();
        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                ((ChoiceRequestEvent) events.take()).response().answer(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AskUserTool tool = new AskUserTool();
        ToolResult result = tool.executeInteractive(call("A", "B"), events, new AtomicBoolean(false));
        responder.join(1000);

        assertTrue(result.isError());
        assertTrue(result.content().contains("取消"), "取消应回传含「取消」的失败原因：" + result.content());
    }

    @Test
    void emptyOptionsReturnFailureWithoutEmittingEvent() {
        BlockingQueue<AgentEvent> events = queue();
        AskUserTool tool = new AskUserTool();
        ToolResult result = tool.executeInteractive(call(), events, new AtomicBoolean(false));

        assertTrue(result.isError());
        assertTrue(result.content().contains("选项不能为空"));
        assertTrue(events.isEmpty(), "options 为空不应弹菜单事件");
    }

    @Test
    void nonStringOptionReturnFailureWithoutEmittingEvent() {
        BlockingQueue<AgentEvent> events = queue();
        ToolUseBlock badCall = new ToolUseBlock("toolu_1", "AskUser",
                JSON.createObjectNode().put("question", "q").putArray("options").add(42));
        AskUserTool tool = new AskUserTool();
        ToolResult result = tool.executeInteractive(badCall, events, new AtomicBoolean(false));

        assertTrue(result.isError());
        assertTrue(result.content().contains("选项不能为空"));
        assertTrue(events.isEmpty(), "非字符串选项不应弹菜单事件");
    }

    @Test
    void inputSchemaDeclaresStringArrayOptions() {
        AskUserTool tool = new AskUserTool();
        JsonNode schema = tool.inputSchema();
        assertEquals("object", schema.path("type").asText());
        assertEquals("array", schema.path("properties").path("options").path("type").asText());
        assertEquals("string", schema.path("properties").path("options").path("items").path("type").asText());
        assertTrue(schema.path("required").toString().contains("question"));
        assertTrue(schema.path("required").toString().contains("options"));
        assertEquals(Permission.READ, tool.permission(), "AskUser 应绕过确认门");
    }

    @Test
    void executeDefensivePathReturnsFailure() {
        AskUserTool tool = new AskUserTool();
        assertTrue(tool.execute(JSON.createObjectNode(), null).isError());
    }
}
