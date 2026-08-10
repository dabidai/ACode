package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话编排：维护完整消息历史，组装请求时按上下文窗口上限从最早消息开始丢弃。
 * token 估算按字符数 ÷ 4 粗略计算；兜底规则：当前问题本身超限时只保留该问题（避免死循环）。
 */
public class Conversation {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final String model;
    private final boolean thinking;
    private final int maxTokens;
    private final int maxContextTokens;

    public Conversation(String model, boolean thinking, int maxTokens, int maxContextTokens) {
        this.model = model;
        this.thinking = thinking;
        this.maxTokens = maxTokens;
        this.maxContextTokens = maxContextTokens;
    }

    /** 追加一条消息到完整历史；截断只发生在组装请求时，不改变已存历史 */
    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    public int messageCount() {
        return messages.size();
    }

    public List<ChatMessage> history() {
        return Collections.unmodifiableList(messages);
    }

    /** 按字符数 ÷ 4 估算 token 数 */
    public static int estimateTokens(String text) {
        return text.length() / 4;
    }

    /** 组装请求：携带完整历史，超出窗口时从最早开始丢弃，直到总量放得下 */
    public ChatRequest buildRequest() {
        return ChatRequest.builder()
                .model(model)
                .thinking(thinking)
                .maxTokens(maxTokens)
                .messages(trim())
                .build();
    }

    private List<ChatMessage> trim() {
        if (estimateTotal(messages) <= maxContextTokens) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages);
        while (result.size() > 1 && estimateTotal(result) > maxContextTokens) {
            result.remove(0);
        }
        return result;
    }

    private static int estimateTotal(List<ChatMessage> list) {
        return list.stream().mapToInt(m -> estimateTokens(m.content())).sum();
    }
}
