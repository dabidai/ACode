package com.acode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ofFactoryWrapsSingleTextBlock() {
        ChatMessage m = ChatMessage.of(USER, "你好");
        assertEquals(1, m.blocks().size());
        assertInstanceOf(TextBlock.class, m.blocks().get(0));
        assertEquals("你好", m.content());
    }

    @Test
    void ofMessageRoundTripsTextConsistently() throws Exception {
        ChatMessage original = ChatMessage.of(USER, "你好，世界");
        String json = JSON.writeValueAsString(original);
        assertTrue(json.contains("\"content\":["));
        ChatMessage restored = JSON.readValue(json, ChatMessage.class);
        assertEquals("你好，世界", restored.content());
        assertInstanceOf(TextBlock.class, restored.blocks().get(0));
    }

    @Test
    void toolUseMessageRoundTripsFields() throws Exception {
        ChatMessage m = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("toolu_1", "ReadFile",
                        JSON.readTree("{\"file_path\":\"pom.xml\"}"))));
        String json = JSON.writeValueAsString(m);
        assertTrue(json.contains("\"type\":\"tool_use\""));
        assertTrue(json.contains("\"id\":\"toolu_1\""));
        assertTrue(json.contains("\"name\":\"ReadFile\""));
        ChatMessage restored = JSON.readValue(json, ChatMessage.class);
        ToolUseBlock block = assertInstanceOf(ToolUseBlock.class, restored.blocks().get(0));
        assertEquals("toolu_1", block.id());
        assertEquals("ReadFile", block.name());
        assertEquals("pom.xml", block.input().get("file_path").asText());
    }

    @Test
    void toolResultMessageRoundTripsFields() throws Exception {
        ChatMessage m = new ChatMessage(USER, List.of(
                new ToolResultBlock("toolu_1", "文件内容", true)));
        String json = JSON.writeValueAsString(m);
        assertTrue(json.contains("\"type\":\"tool_result\""));
        assertTrue(json.contains("\"tool_use_id\":\"toolu_1\""));
        assertTrue(json.contains("\"is_error\":true"));
        ChatMessage restored = JSON.readValue(json, ChatMessage.class);
        ToolResultBlock block = assertInstanceOf(ToolResultBlock.class, restored.blocks().get(0));
        assertEquals("toolu_1", block.toolUseId());
        assertEquals("文件内容", block.content());
        assertTrue(block.isError());
    }

    @Test
    void legacyStringContentDeserializesToTextBlock() throws Exception {
        ChatMessage restored = JSON.readValue(
                "{\"role\":\"USER\",\"content\":\"旧版文本\"}", ChatMessage.class);
        assertEquals(USER, restored.role());
        assertEquals("旧版文本", restored.content());
        assertInstanceOf(TextBlock.class, restored.blocks().get(0));
    }
}
