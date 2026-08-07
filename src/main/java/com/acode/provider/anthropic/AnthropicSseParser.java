package com.acode.provider.anthropic;

import com.acode.provider.AuthException;
import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.acode.provider.RateLimitException;
import com.acode.provider.ServerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把 Anthropic SSE 事件（data 行 JSON）分发为 ChatListener 回调。
 * 只输出 text_delta 内容；thinking 相关事件忽略；error/message_stop 结束流。
 */
public final class AnthropicSseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicSseParser() {
    }

    public static void handle(String data, ChatListener listener) {
        try {
            JsonNode node = JSON.readTree(data);
            switch (node.path("type").asText()) {
                case "content_block_delta" -> handleDelta(node, listener);
                case "error" -> throw classify(node.path("error"));
                case "message_stop" -> listener.onComplete();
                default -> {
                    // message_start / content_block_start / content_block_stop / message_delta 忽略
                }
            }
        } catch (JsonProcessingException e) {
            listener.onError(new InvalidRequestException("响应解析失败：" + e.getMessage(), e));
        } catch (ProviderException e) {
            listener.onError(e);
        }
    }

    private static void handleDelta(JsonNode node, ChatListener listener) {
        JsonNode delta = node.path("delta");
        if ("text_delta".equals(delta.path("type").asText())) {
            listener.onDelta(delta.path("text").asText());
        }
        // thinking_delta 等不输出
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
