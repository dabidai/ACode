package com.acode.provider.openai;

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
 * OpenAI 兼容后端实现（chat completions + SSE）。
 * 适配 OpenAI 官方与 DeepSeek 等兼容服务；reasoning_content（如 deepseek-reasoner）不输出。
 * base_url 需含 /v1 前缀（如 https://api.openai.com/v1 或 https://api.deepseek.com/v1）。
 */
public class OpenAiProvider implements ChatProvider {

    private static final String ENDPOINT = "/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;

    public OpenAiProvider(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void streamChat(ChatRequest request, ChatListener listener) {
        try {
            String body = buildBody(request);
            ProviderHttpClient.Result result = ProviderHttpClient.send(
                    baseUrl + ENDPOINT, body,
                    Map.of("Authorization", "Bearer " + apiKey));
            try (InputStream in = result.body()) {
                SseParser.parse(in, (eventType, data) -> OpenAiSseParser.handle(data, listener));
            }
        } catch (ProviderException e) {
            listener.onError(e);
        } catch (IOException e) {
            listener.onError(new NetworkException("读取响应流失败：" + e.getMessage(), e));
        }
    }

    private String buildBody(ChatRequest request) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("model", request.model());
            root.put("stream", true);
            root.put("max_tokens", request.maxTokens());
            ArrayNode messages = root.putArray("messages");
            for (ChatMessage message : request.messages()) {
                messages.addObject()
                        .put("role", message.role().name().toLowerCase())
                        .put("content", message.content());
            }
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("请求体构建失败：" + e.getMessage(), e);
        }
    }
}
