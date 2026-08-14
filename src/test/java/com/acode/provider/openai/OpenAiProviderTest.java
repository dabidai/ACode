package com.acode.provider.openai;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolRegistry;
import com.acode.tool.impl.ReadFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProviderTest {

    private final OpenAiProvider provider =
            new OpenAiProvider("https://api.deepseek.com/v1", "test-key", false);
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(ChatRequest request) throws Exception {
        return json.readTree(provider.buildBody(request));
    }

    @Test
    void toolsListEmittedAsOpenAiToolArrayWhenSet() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .tools(registry.list())
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode tools = body(request).path("tools");
        assertTrue(tools.isArray(), "应输出 OpenAI tools 数组");
        JsonNode entry = tools.get(0);
        assertEquals("function", entry.path("type").asText(), "OpenAI 工具 type 为 function");
        assertEquals("ReadFile", entry.path("function").path("name").asText());
        assertTrue(entry.path("function").hasNonNull("description"));
        assertTrue(entry.path("function").path("parameters").isObject());
    }

    @Test
    void noToolsFieldWhenNotSet() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        assertFalse(body(request).has("tools"));
    }

    @Test
    void toolUseMessageEmittedAsToolCalls() throws Exception {
        ToolUseBlock toolUse = new ToolUseBlock("id-1", "ReadFile",
                json.createObjectNode().put("file_path", "a.txt"));
        ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(toolUse));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "读文件"))
                .message(assistant)
                .build();
        JsonNode msg = body(request).path("messages").get(1);
        assertEquals("assistant", msg.path("role").asText());
        assertTrue(msg.path("content").isNull(), "纯工具调用消息 content 为 null");
        JsonNode call = msg.path("tool_calls").get(0);
        assertEquals("id-1", call.path("id").asText());
        assertEquals("function", call.path("type").asText());
        assertEquals("ReadFile", call.path("function").path("name").asText());
        JsonNode args = json.readTree(call.path("function").path("arguments").asText());
        assertEquals("a.txt", args.path("file_path").asText(), "arguments 应为参数 JSON 字符串");
    }

    @Test
    void toolUseMessageWithTextKeepsContentAndToolCalls() throws Exception {
        ToolUseBlock toolUse = new ToolUseBlock("id-1", "Glob",
                json.createObjectNode().put("pattern", "**/*.java"));
        ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new com.acode.provider.TextBlock("先搜索"),
                toolUse));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "找 java 文件"))
                .message(assistant)
                .build();
        JsonNode msg = body(request).path("messages").get(1);
        assertEquals("先搜索", msg.path("content").asText(), "文本块进 content");
        assertEquals(1, msg.path("tool_calls").size());
    }

    @Test
    void toolResultMessageEmittedAsToolMessages() throws Exception {
        ToolResultBlock result = new ToolResultBlock("id-1", "文件内容", false);
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, List.of(result));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(user)
                .build();
        JsonNode msg = body(request).path("messages").get(0);
        assertEquals("tool", msg.path("role").asText(), "工具结果用 role=tool");
        assertEquals("id-1", msg.path("tool_call_id").asText());
        assertEquals("文件内容", msg.path("content").asText());
    }

    @Test
    void failedToolResultGetsErrorPrefix() throws Exception {
        ToolResultBlock result = new ToolResultBlock("id-2", "文件不存在", true);
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, List.of(result));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(user)
                .build();
        String content = body(request).path("messages").get(0).path("content").asText();
        assertTrue(content.startsWith("[工具执行失败]"), "失败结果应在回传内容中标出");
        assertTrue(content.contains("文件不存在"));
    }

    @Test
    void multipleToolResultsExpandToMultipleToolMessages() throws Exception {
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ToolResultBlock("id-1", "成功", false),
                new ToolResultBlock("id-2", "失败", true)));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(user)
                .build();
        JsonNode messages = body(request).path("messages");
        assertEquals(2, messages.size(), "两条工具结果应展开为两条 role=tool 消息");
        assertEquals("tool", messages.get(0).path("role").asText());
        assertEquals("id-1", messages.get(0).path("tool_call_id").asText());
        assertEquals("id-2", messages.get(1).path("tool_call_id").asText());
    }

    @Test
    void plainTextMessagesKeepRoleAndContent() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .message(ChatMessage.of(ChatMessage.Role.ASSISTANT, "你好，请问"))
                .build();
        JsonNode messages = body(request).path("messages");
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("你好", messages.get(0).path("content").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertFalse(messages.get(1).has("tool_calls"), "纯文本 assistant 不应有 tool_calls");
    }

    @Test
    void systemMessageEmittedAsSystemRole() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.SYSTEM, "你是助手"))
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode messages = body(request).path("messages");
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("你是助手", messages.get(0).path("content").asText());
    }
}
