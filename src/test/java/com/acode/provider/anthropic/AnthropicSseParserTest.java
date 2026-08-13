package com.acode.provider.anthropic;

import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.acode.provider.RateLimitException;
import com.acode.provider.ToolUseBlock;
import com.acode.sse.SseParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用录制的真实 Anthropic 事件片段验证解析：
 * thinking 内容不输出、text_delta 输出、tool_use 碎片拼接、message_stop 结束、error 分类。
 */
class AnthropicSseParserTest {

    private final List<String> deltas = new ArrayList<>();
    private final List<ToolUseBlock> toolUses = new ArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<ProviderException> error = new AtomicReference<>();

    private final ChatListener listener = new ChatListener() {
        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onToolUse(ToolUseBlock toolUse) {
            toolUses.add(toolUse);
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }

        @Override
        public void onError(ProviderException e) {
            error.set(e);
        }
    };

    private void parse(String sse) throws IOException {
        AnthropicSseParser parser = new AnthropicSseParser();
        SseParser.parse(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                (eventType, data) -> parser.handle(data, listener));
    }

    @Test
    void thinking不输出正文文本输出并正常结束() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_01","role":"assistant","content":[]}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"let me think"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"about it"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"你好，"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"世界！"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        parse(sse);
        assertEquals(List.of("你好，", "世界！"), deltas);
        assertTrue(completed.get());
        assertNull(error.get());
    }

    @Test
    void error事件分类为限流() throws IOException {
        String sse = """
                event: error
                data: {"type":"error","error":{"type":"rate_limit_error","message":"Rate limit exceeded"}}
                """;
        parse(sse);
        assertFalse(completed.get());
        assertInstanceOf(RateLimitException.class, error.get());
    }

    @Test
    void 非JSON数据转解析错误() {
        new AnthropicSseParser().handle("not json at all", listener);
        assertFalse(completed.get());
        assertInstanceOf(InvalidRequestException.class, error.get());
    }

    @Test
    void toolUse碎片跨多次delta拼出完整参数() throws IOException {
        String sse = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"ReadFile","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"file_path\\":\\"a"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":".txt\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        parse(sse);
        assertEquals(1, toolUses.size());
        ToolUseBlock block = toolUses.get(0);
        assertEquals("toolu_1", block.id());
        assertEquals("ReadFile", block.name());
        assertEquals("a.txt", block.input().path("file_path").asText());
        assertTrue(deltas.isEmpty(), "tool_use 不应产生文本增量");
        assertTrue(completed.get());
        assertNull(error.get());
    }

    @Test
    void toolUse与thinking混排不串块() throws IOException {
        String sse = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"plan"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"steps"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"Bash","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"echo hi\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        parse(sse);
        assertEquals(1, toolUses.size());
        assertEquals("echo hi", toolUses.get(0).input().path("command").asText());
        assertTrue(deltas.isEmpty(), "thinking 与 tool_use 都不该产生文本增量");
        assertTrue(completed.get());
        assertNull(error.get());
    }
}
