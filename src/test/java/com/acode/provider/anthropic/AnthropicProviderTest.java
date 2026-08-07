package com.acode.provider.anthropic;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
    void system消息进顶级字段user进messages() throws Exception {
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .message(ChatMessage.of(ChatMessage.Role.SYSTEM, "你是助手"))
                .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
                .message(ChatMessage.of(ChatMessage.Role.ASSISTANT, "你好，请问"))
                .build();
        JsonNode root = body(request);
        assertEquals("你是助手", root.path("system").asText());
        assertEquals(2, root.path("messages").size());
        assertEquals("user", root.path("messages").get(0).path("role").asText());
        assertEquals("assistant", root.path("messages").get(1).path("role").asText());
    }
}
