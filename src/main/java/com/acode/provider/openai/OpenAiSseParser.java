package com.acode.provider.openai;

import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个流的事件解析器：把 OpenAI 兼容 SSE 事件（data 行）分发为 ChatListener 回调。
 * 输出 choices[0].delta.content；[DONE] 结束；error 对象报错；reasoning_content 忽略。
 * 工具调用经 choices[0].delta.tool_calls[] 下发：按 index 累积 id/name/arguments 碎片，
 * finish_reason=tool_calls 或 [DONE] 时 flush 为 onToolUse。
 */
public final class OpenAiSseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 正在累积的 tool_call 参数碎片，按 index 索引（一次可能并发多个 tool_call） */
    private final Map<Integer, ToolCallAccumulator> toolCalls = new HashMap<>();

    public void handle(String data, ChatListener listener) {
        if ("[DONE]".equals(data)) {
            flushToolCalls(listener);
            listener.onComplete();
            return;
        }
        try {
            JsonNode node = JSON.readTree(data);
            if (node.has("error")) {
                throw new InvalidRequestException(
                        "请求错误：" + node.path("error").path("message").asText("无详情"));
            }
            JsonNode choice = node.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode toolCallsDelta = delta.path("tool_calls");
            if (toolCallsDelta.isArray()) {
                for (JsonNode tc : toolCallsDelta) {
                    handleToolCallDelta(tc);
                }
            }
            String content = delta.path("content").asText(null);
            if (content != null && !content.isEmpty()) {
                listener.onDelta(content);
            }
            // 流结束信号：tool_calls（工具调用完成）或 stop（普通回复）
            String finish = choice.path("finish_reason").asText(null);
            if ("tool_calls".equals(finish) || "stop".equals(finish)) {
                flushToolCalls(listener);
            }
        } catch (JsonProcessingException e) {
            listener.onError(new InvalidRequestException("响应解析失败：" + e.getMessage(), e));
        } catch (ProviderException e) {
            listener.onError(e);
        }
    }

    private void handleToolCallDelta(JsonNode tc) {
        int index = tc.path("index").asInt(-1);
        ToolCallAccumulator acc = toolCalls.computeIfAbsent(index,
                i -> new ToolCallAccumulator(null, null, new StringBuilder()));
        String id = tc.path("id").asText(null);
        if (id != null && !id.isEmpty()) {
            acc.id = id;
        }
        String name = tc.path("function").path("name").asText(null);
        if (name != null && !name.isEmpty()) {
            acc.name = name;
        }
        String args = tc.path("function").path("arguments").asText(null);
        if (args != null && !args.isEmpty()) {
            acc.arguments.append(args);
        }
    }

    /** 累积的 tool_call 全部转成 onToolUse 回调；参数 JSON 解析失败报错。 */
    private void flushToolCalls(ChatListener listener) {
        for (ToolCallAccumulator acc : toolCalls.values()) {
            if (acc.id == null || acc.name == null) {
                continue;
            }
            try {
                JsonNode input = acc.arguments.length() == 0
                        ? JSON.createObjectNode()
                        : JSON.readTree(acc.arguments.toString());
                listener.onToolUse(new ToolUseBlock(acc.id, acc.name, input));
            } catch (JsonProcessingException e) {
                listener.onError(new InvalidRequestException(
                        "tool_calls 参数解析失败：" + e.getMessage(), e));
            }
        }
        toolCalls.clear();
    }

    private static final class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments;

        ToolCallAccumulator(String id, String name, StringBuilder arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }
}
