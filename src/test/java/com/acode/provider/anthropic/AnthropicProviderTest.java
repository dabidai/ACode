package com.acode.provider.anthropic;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolRegistry;
import com.acode.tool.impl.ReadFileTool;
import com.acode.tool.impl.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderTest {

    private final AnthropicProvider provider =
            new AnthropicProvider("https://api.anthropic.com", "test-key");
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(ChatRequest request) throws Exception {
        return json.readTree(provider.buildBody(request));
    }

    @Test
    void 开启thinking时请求体含thinking参数且maxTokens充足() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .thinking(true)
                .maxTokens(4096)
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode root = body(request);
        assertTrue(root.path("stream").asBoolean(), "Anthropic 流式必须传 stream=true");
        assertEquals("enabled", root.path("thinking").path("type").asText());
        int budget = root.path("thinking").path("budget_tokens").asInt();
        assertTrue(budget >= 1024, "budget_tokens 不能低于 Anthropic 下限 1024");
        assertTrue(root.path("max_tokens").asInt() >= budget + 1024,
                "max_tokens 必须大于 budget_tokens");
    }

    @Test
    void 不开启thinking时无thinking字段() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .thinking(false)
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode root = body(request);
        assertFalse(root.has("thinking"));
    }

    @Test
    void systemEmittedAsTextBlockArrayWithCacheControl() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.SYSTEM, "你是助手"))
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .message(ChatMessage.of(ChatMessage.Role.ASSISTANT, "你好，请问"))
                .build();
        JsonNode root = body(request);
        JsonNode system = root.path("system");
        assertTrue(system.isArray(), "system 应为 content block 数组");
        assertEquals(1, system.size());
        assertEquals("text", system.get(0).path("type").asText());
        assertEquals("你是助手", system.get(0).path("text").asText());
        assertEquals("ephemeral", system.get(0).path("cache_control").path("type").asText());
        assertEquals(2, root.path("messages").size());
        assertEquals("user", root.path("messages").get(0).path("role").asText());
        assertEquals("assistant", root.path("messages").get(1).path("role").asText());
    }

    @Test
    void plainTextMessageContentIsStructuredTextBlock() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode content = body(request).path("messages").get(0).path("content");
        assertTrue(content.isArray(), "content 应为数组而非纯文本");
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("你好", content.get(0).path("text").asText());
    }

    @Test
    void toolUseMessageEmittedAsToolUseBlock() throws Exception {
        ToolUseBlock toolUse = new ToolUseBlock("id-1", "ReadFile",
                json.createObjectNode().put("file_path", "a.txt"));
        ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(toolUse));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "读文件"))
                .message(assistant)
                .build();
        JsonNode content = body(request).path("messages").get(1).path("content");
        assertEquals("tool_use", content.get(0).path("type").asText());
        assertEquals("id-1", content.get(0).path("id").asText());
        assertEquals("ReadFile", content.get(0).path("name").asText());
        assertEquals("a.txt", content.get(0).path("input").path("file_path").asText());
    }

    @Test
    void toolResultMessageEmittedAsToolResultBlock() throws Exception {
        ToolResultBlock result = new ToolResultBlock("id-1", "文件内容", false);
        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, List.of(result));
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(user)
                .build();
        JsonNode content = body(request).path("messages").get(0).path("content");
        assertEquals("tool_result", content.get(0).path("type").asText());
        assertEquals("id-1", content.get(0).path("tool_use_id").asText());
        assertEquals("文件内容", content.get(0).path("content").asText());
        assertFalse(content.get(0).path("is_error").asBoolean());
    }

    @Test
    void toolsListEmittedAsAnthropicToolArrayWhenSet() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .tools(registry.list())
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode tools = body(request).path("tools");
        assertTrue(tools.isArray());
        assertEquals("ReadFile", tools.get(0).path("name").asText());
        assertTrue(tools.get(0).hasNonNull("description"));
        assertTrue(tools.get(0).path("input_schema").isObject());
    }

    @Test
    void toolsLastElementGetsCacheControlOnly() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .tools(registry.list())
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        JsonNode tools = body(request).path("tools");
        assertTrue(tools.isArray());
        assertEquals(2, tools.size());
        assertFalse(tools.get(0).has("cache_control"), "非末工具不应带 cache_control");
        assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asText());
    }

    @Test
    void noToolsFieldWhenNotSet() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .build();
        assertFalse(body(request).has("tools"));
    }
}
