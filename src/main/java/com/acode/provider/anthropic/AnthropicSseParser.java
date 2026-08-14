package com.acode.provider.anthropic;

import com.acode.provider.AuthException;
import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.acode.provider.RateLimitException;
import com.acode.provider.ServerException;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个流的事件解析器：把 Anthropic SSE data 行分发为 ChatListener 回调。
 * 处理 text_delta 输出与 tool_use 块：content_block_start 起累积器，
 * input_json_delta 逐段拼接参数 JSON 碎片，content_block_stop 时解析并回调。
 * thinking 事件忽略；error/message_stop 结束流。
 */
public final class AnthropicSseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 正在累积的 tool_use 块，按 block index 索引，避免与 thinking/text 混排串块 */
    private final Map<Integer, ToolUseAccumulator> toolUses = new HashMap<>();

    /** message_delta 下发的流结束原因（end_turn / max_tokens 等），message_stop 时透传 */
    private String stopReason;

    public void handle(String data, ChatListener listener) {
        try {
            JsonNode node = JSON.readTree(data);
            switch (node.path("type").asText()) {
                case "content_block_start" -> handleBlockStart(node);
                case "content_block_delta" -> handleDelta(node, listener);
                case "content_block_stop" -> handleBlockStop(node, listener);
                case "error" -> throw classify(node.path("error"));
                case "message_delta" -> {
                    String reason = node.path("delta").path("stop_reason").asText("");
                    if (!reason.isEmpty()) {
                        stopReason = reason;
                    }
                }
                case "message_stop" -> listener.onComplete(stopReason);
                default -> {
                    // message_start 忽略
                }
            }
        } catch (JsonProcessingException e) {
            listener.onError(new InvalidRequestException("响应解析失败：" + e.getMessage(), e));
        } catch (ProviderException e) {
            listener.onError(e);
        }
    }

    private void handleBlockStart(JsonNode node) {
        JsonNode block = node.path("content_block");
        if ("tool_use".equals(block.path("type").asText())) {
            int index = node.path("index").asInt(-1);
            toolUses.put(index, new ToolUseAccumulator(
                    block.path("id").asText(), block.path("name").asText(), new StringBuilder()));
        }
    }

    private void handleDelta(JsonNode node, ChatListener listener) {
        JsonNode delta = node.path("delta");
        switch (delta.path("type").asText()) {
            case "text_delta" -> listener.onDelta(delta.path("text").asText());
            case "input_json_delta" -> {
                ToolUseAccumulator acc = toolUses.get(node.path("index").asInt(-1));
                if (acc != null) {
                    acc.fragments.append(delta.path("partial_json").asText());
                }
            }
            default -> {
                // thinking_delta 等不输出
            }
        }
    }

    private void handleBlockStop(JsonNode node, ChatListener listener) {
        ToolUseAccumulator acc = toolUses.remove(node.path("index").asInt(-1));
        if (acc != null) {
            try {
                JsonNode input = JSON.readTree(acc.fragments.toString());
                listener.onToolUse(new ToolUseBlock(acc.id, acc.name, input));
            } catch (JsonProcessingException e) {
                listener.onError(new InvalidRequestException(
                        "tool_use 参数解析失败：" + e.getMessage(), e));
            }
        }
    }

    private record ToolUseAccumulator(String id, String name, StringBuilder fragments) {
    }

    private static ProviderException classify(JsonNode error) {
        String type = error.path("type").asText();
        String message = error.path("message").asText("无详情");
        return switch (type) {
            case "authentication_error", "permission_error" -> new AuthException("认证失败：" + message);
            case "rate_limit_error" -> new RateLimitException("限流：" + message);
            case "overloaded_error", "api_error" -> new ServerException("服务端错误：" + message);
            default -> new InvalidRequestException("请求错误（" + type + "）：" + message);
        };
    }
}
