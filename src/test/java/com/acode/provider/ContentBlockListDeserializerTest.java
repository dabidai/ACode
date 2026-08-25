package com.acode.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版会话文件兼容：content 字段为纯字符串（而非内容块数组）时，
 * ContentBlockListDeserializer 应包成单个 TextBlock 反序列化。
 */
class ContentBlockListDeserializerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void legacyStringContentDeserializesToTextBlock() throws Exception {
        ChatMessage message = JSON.readValue(
                "{\"role\":\"USER\",\"content\":\"纯文本内容\"}", ChatMessage.class);

        List<ContentBlock> blocks = message.blocks();
        assertEquals(1, blocks.size(), "旧格式纯字符串应包成单个内容块");
        TextBlock text = assertInstanceOf(TextBlock.class, blocks.get(0), "应为 TextBlock");
        assertEquals("纯文本内容", text.text(), "文本内容应原样保留");
        assertEquals("纯文本内容", message.content(), "content() 拼接结果应一致");
        assertEquals(ChatMessage.Role.USER, message.role());
    }

    @Test
    void blockArrayContentStillDeserializes() throws Exception {
        ChatMessage message = JSON.readValue(
                "{\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"新版内容\"}]}",
                ChatMessage.class);
        assertEquals(1, message.blocks().size(), "新版内容块数组应正常反序列化");
        assertTrue(message.content().contains("新版内容"));
    }
}
