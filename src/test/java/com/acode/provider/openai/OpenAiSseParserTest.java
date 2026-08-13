package com.acode.provider.openai;

import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.sse.SseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final List<ToolUseBlock> toolUses = new ArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<ProviderException> error = new AtomicReference<>();

    private static final ObjectMapper JSON = new ObjectMapper();

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
        OpenAiSseParser parser = new OpenAiSseParser();
        SseParser.parse(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
                (eventType, data) -> parser.handle(data, listener));
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
        new OpenAiSseParser().handle("garbage", listener);
        assertInstanceOf(InvalidRequestException.class, error.get());
    }

    @Test
    void toolCallsFragmentsAssembledIntoCompleteArguments() throws IOException {
        // 参数 JSON 跨 2 个 delta 碎片下发，finish_reason=tool_calls 结束
        String args1 = JSON.writeValueAsString("{\"file_path\":");   // {"file_path":
        String args2 = JSON.writeValueAsString("\"pom.xml\"}");      // "pom.xml"}
        String sse =
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"ReadFile\",\"arguments\":\"\"}}]}}]}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"function\":{\"arguments\":" + args1 + "}}]}}]}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"function\":{\"arguments\":" + args2 + "}}]}}]}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n";
        parse(sse);
        assertEquals(1, toolUses.size(), "应拼出 1 个 tool_call");
        ToolUseBlock use = toolUses.get(0);
        assertEquals("call_1", use.id());
        assertEquals("ReadFile", use.name());
        assertEquals("pom.xml", use.input().path("file_path").asText(), "碎片拼出的参数应与完整 JSON 一致");
        assertTrue(completed.get());
        assertNull(error.get());
    }

    @Test
    void toolCallsFlushedOnDoneEvenWithoutFinishReason() throws IOException {
        // 某些兼容服务只发 [DONE] 不发 finish_reason，也须 flush 出 tool_call
        String args = JSON.writeValueAsString("{}");
        String sse =
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"type\":\"function\","
                + "\"function\":{\"name\":\"Glob\",\"arguments\":" + args + "}}]}}]}\n\n"
                + "data: [DONE]\n";
        parse(sse);
        assertEquals(1, toolUses.size());
        assertEquals("call_2", toolUses.get(0).id());
        assertEquals("Glob", toolUses.get(0).name());
        assertTrue(completed.get());
    }

    @Test
    void twoToolCallsAssembledByIndex() throws IOException {
        String args1 = JSON.writeValueAsString("{\"pattern\":\"**/*.java\"}");
        String args2 = JSON.writeValueAsString("{\"command\":\"echo hi\"}");
        String sse =
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"Glob\",\"arguments\":" + args1 + "}},"
                + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":" + args2 + "}}]}}]}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n";
        parse(sse);
        assertEquals(2, toolUses.size());
        assertEquals("Glob", toolUses.get(0).name());
        assertEquals("**/*.java", toolUses.get(0).input().path("pattern").asText());
        assertEquals("Bash", toolUses.get(1).name());
        assertEquals("echo hi", toolUses.get(1).input().path("command").asText());
    }
}
