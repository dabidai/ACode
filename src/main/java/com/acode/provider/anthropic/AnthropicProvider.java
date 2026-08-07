package com.acode.provider.anthropic;

import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.NetworkException;
import com.acode.provider.ProviderException;
import com.acode.provider.ProviderHttpClient;
import com.acode.sse.SseParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Anthropic Claude 后端实现（messages API + SSE）。
 * 开启 thinking 时请求带 thinking 参数，思考内容不输出，仅 text_delta 回传。
 */
public class AnthropicProvider implements ChatProvider {

    private static final String ENDPOINT = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;

    public AnthropicProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void streamChat(ChatRequest request, ChatListener listener) {
        try {
            String body = buildBody(request);
            ProviderHttpClient.Result result = ProviderHttpClient.send(
                    baseUrl + ENDPOINT, body,
                    Map.of("x-api-key", apiKey, "anthropic-version", ANTHROPIC_VERSION));
            try (InputStream in = result.body()) {
                SseParser.parse(in, (eventType, data) -> AnthropicSseParser.handle(data, listener));
            }
        } catch (ProviderException e) {
            listener.onError(e);
        } catch (IOException e) {
            listener.onError(new NetworkException("读取响应流失败：" + e.getMessage(), e));
        }
    }

    /** 包可见供单测断言请求体结构 */
    String buildBody(ChatRequest request) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", request.model());
            root.put("stream", true);
            int maxTokens = request.maxTokens();
            if (request.thinking()) {
                int budget = Math.max(1024, Math.min(2048, maxTokens / 2));
                maxTokens = Math.max(maxTokens, budget + 1024);
                root.putObject("thinking")
                        .put("type", "enabled")
                        .put("budget_tokens", budget);
            }
            root.put("max_tokens", maxTokens);

            StringBuilder system = new StringBuilder();
            ArrayNode messages = root.putArray("messages");
            for (ChatMessage message : request.messages()) {
                switch (message.role()) {
                    case SYSTEM -> {
                        if (!system.isEmpty()) {
                            system.append('\n');
                        }
                        system.append(message.content());
                    }
                    case USER, ASSISTANT -> messages.addObject()
                            .put("role", message.role().name().toLowerCase())
                            .put("content", message.content());
                }
            }
            if (!system.isEmpty()) {
                root.put("system", system.toString());
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("请求体构建失败：" + e.getMessage(), e);
        }
    }
}
