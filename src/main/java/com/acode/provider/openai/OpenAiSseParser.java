package com.acode.provider.openai;

import com.acode.provider.ChatListener;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.ProviderException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把 OpenAI 兼容 SSE 事件（data 行）分发为 ChatListener 回调。
 * 只输出 choices[0].delta.content；[DONE] 结束；error 对象报错；reasoning_content 忽略。
 */
public final class OpenAiSseParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiSseParser() {
    }

    public static void handle(String data, ChatListener listener) {
        if ("[DONE]".equals(data)) {
            listener.onComplete();
            return;
        }
        try {
            JsonNode node = JSON.readTree(data);
            if (node.has("error")) {
                throw new InvalidRequestException(
                        "请求错误：" + node.path("error").path("message").asText("无详情"));
            }
            String content = node.path("choices").path(0).path("delta").path("content").asText(null);
            if (content != null && !content.isEmpty()) {
                listener.onDelta(content);
            }
        } catch (JsonProcessingException e) {
            listener.onError(new InvalidRequestException("响应解析失败：" + e.getMessage(), e));
        } catch (ProviderException e) {
            listener.onError(e);
        }
    }
}
