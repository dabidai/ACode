package com.acode.provider.openai;

import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
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
 * 用录制片段验证 OpenAI 兼容 SSE 解析：
 * content 增量输出、[DONE] 结束、reasoning_content 忽略、error 报错。
 */
class OpenAiSseParserTest {

    private final List<String> deltas = new ArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<ProviderException> error = new AtomicReference<>();

    private final ChatListener listener = new ChatListener() {
        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
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
        SseParser.parse(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                (eventType, data) -> OpenAiSseParser.handle(data, listener));
    }

    @Test
    void 普通流输出content并DONE结束() throws IOException {
        String sse = """
                data: {"id":"x","object":"chat.completion.chunk","model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

                data: {"id":"x","choices":[{"index":0,"delta":{"content":"你"}}]}

                data: {"id":"x","choices":[{"index":0,"delta":{"content":"好"}}]}

                data: [DONE]
                """;
        parse(sse);
        assertEquals(List.of("你", "好"), deltas);
        assertTrue(completed.get());
        assertNull(error.get());
    }

    @Test
    void reasoningContent不输出() throws IOException {
        String sse = """
                data: {"id":"x","choices":[{"index":0,"delta":{"reasoning_content":"let me think deeply"}}]}

                data: [DONE]
                """;
        parse(sse);
        assertTrue(deltas.isEmpty());
        assertTrue(completed.get());
    }

    @Test
    void 流内error事件报错() throws IOException {
        String sse = """
                data: {"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}
                """;
        parse(sse);
        assertFalse(completed.get());
        InvalidRequestException e = assertInstanceOf(InvalidRequestException.class, error.get());
        assertTrue(e.getMessage().contains("Incorrect API key"));
    }

    @Test
    void 非JSON数据转解析错误() {
        OpenAiSseParser.handle("garbage", listener);
        assertInstanceOf(InvalidRequestException.class, error.get());
    }
}
