package com.acode.session;

import com.acode.provider.ChatMessage;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void roundTripsPlainTextLine() {
        ChatMessage original = ChatMessage.of(USER, "你好");
        SessionEntry decoded = SessionCodec.decode(SessionCodec.encode(original, 1763951405L)).orElseThrow();
        assertEquals(USER, decoded.message().role());
        assertEquals("你好", decoded.message().content());
        assertEquals(1763951405L, decoded.ts());
    }

    @Test
    void roundTripsBlockArrayWithToolBlocks() {
        ChatMessage assistant = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("id-1", "ReadFile", JSON.createObjectNode().put("file_path", "a.txt"))));
        ChatMessage toolResult = new ChatMessage(USER, List.of(
                new ToolResultBlock("id-1", "文件内容", false)));

        SessionEntry use = SessionCodec.decode(SessionCodec.encode(assistant, 10L)).orElseThrow();
        ToolUseBlock useBlock = assertInstanceOf(ToolUseBlock.class, use.message().blocks().get(0));
        assertEquals("ReadFile", useBlock.name());
        assertEquals("a.txt", useBlock.input().path("file_path").asText());

        SessionEntry result = SessionCodec.decode(SessionCodec.encode(toolResult, 11L)).orElseThrow();
        ToolResultBlock resultBlock = assertInstanceOf(ToolResultBlock.class,
                result.message().blocks().get(0));
        assertEquals("id-1", resultBlock.toolUseId());
        assertEquals("文件内容", resultBlock.content());
        assertFalse(resultBlock.isError());
    }

    @Test
    void encodedLineContainsNoNewline() {
        String line = SessionCodec.encode(ChatMessage.of(USER, "第一行\n第二行"), 5L);
        assertFalse(line.contains("\n"), "一行一条：编码结果不得含换行");
    }

    @Test
    void encodedLineKeepsPolymorphicTypeDiscriminator() {
        String line = SessionCodec.encode(ChatMessage.of(USER, "x"), 1L);
        assertTrue(line.contains("\"type\":\"text\""), "内容块必须带多态判别字段：" + line);
    }

    @Test
    void decodedAcceptsPlainStringContent() {
        Optional<SessionEntry> entry =
                SessionCodec.decode("{\"role\":\"USER\",\"content\":\"纯字符串\",\"ts\":7}");
        assertEquals("纯字符串", entry.orElseThrow().message().content());
    }

    @Test
    void returnsEmptyForMalformedJson() {
        assertTrue(SessionCodec.decode("{\"role\":\"USER\",\"content\":").isEmpty());
        assertTrue(SessionCodec.decode("not json at all").isEmpty());
        assertTrue(SessionCodec.decode("").isEmpty());
        assertTrue(SessionCodec.decode(null).isEmpty());
    }

    @Test
    void returnsEmptyWhenRequiredFieldMissing() {
        assertTrue(SessionCodec.decode("{\"content\":\"x\",\"ts\":1}").isEmpty(), "缺 role");
        assertTrue(SessionCodec.decode("{\"role\":\"USER\",\"ts\":1}").isEmpty(), "缺 content");
        assertTrue(SessionCodec.decode("{\"role\":\"USER\",\"content\":\"x\"}").isEmpty(), "缺 ts");
        assertTrue(SessionCodec.decode("{\"role\":\"NOPE\",\"content\":\"x\",\"ts\":1}").isEmpty(),
                "role 非合法取值");
    }

    @Test
    void returnsEmptyWhenTsIsNotAnInteger() {
        assertTrue(SessionCodec.decode("{\"role\":\"USER\",\"content\":\"x\",\"ts\":\"1\"}").isEmpty());
        assertTrue(SessionCodec.decode("{\"role\":\"USER\",\"content\":\"x\",\"ts\":1.5}").isEmpty());
    }

    @Test
    void tsIsIntegralSecondsInEncodedLine() {
        String line = SessionCodec.encode(ChatMessage.of(USER, "x"), 1763951405L);
        assertTrue(line.contains("\"ts\":1763951405"), line);
        assertFalse(line.contains("\"ts\":1763951405.0"), "秒应为整型");
    }
}
