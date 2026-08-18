package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话编排：维护完整消息历史，组装请求时按上下文窗口上限从最早消息开始丢弃。
 * token 估算按字符数 ÷ 4 粗略计算；兜底规则：当前问题本身超限时只保留该问题（避免死循环）。
 */
public class Conversation {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final String model;
    private final boolean thinking;
    private final int maxTokens;
    private final int maxContextTokens;

    /** 会话级 system 提示词：会话启动构建一次，会话内字节稳定（可缓存）；不进历史 */
    private String systemPrompt;

    /** 会话级环境快照（渲染好的环境 system-reminder）：每轮作为 messages 首条注入、不进历史 */
    private ChatMessage environment;

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

    /** 把一批工具执行结果作为一条 user 消息追加进历史（Anthropic 要求同批 tool_result 放一条消息） */
    public void addToolResults(List<ToolResultBlock> results) {
        messages.add(new ChatMessage(ChatMessage.Role.USER, new ArrayList<>(results)));
    }

    /** 设置请求携带的工具列表（ch03：单步闭环全程带工具；OpenAI 端忽略） */
    public void setTools(List<Tool> tools) {
        this.tools.clear();
        this.tools.addAll(tools);
    }

    public int messageCount() {
        return messages.size();
    }

    /** 设置会话级 system 提示词（不进历史，只进请求首位） */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /** 设置会话级环境 system-reminder（渲染好的消息；每轮注入 messages 首条、不进历史） */
    public void setEnvironment(ChatMessage environment) {
        this.environment = environment;
    }

    /** 清空全部消息历史（/clear 用）。system prompt 与环境快照留在会话状态，下一轮仍注入。 */
    public void clear() {
        messages.clear();
    }

    public List<ChatMessage> history() {
        return Collections.unmodifiableList(messages);
    }

    /** 按字符数 ÷ 4 估算 token 数 */
    public static int estimateTokens(String text) {
        return text.length() / 4;
    }

    /** 估算一条消息的 token：遍历所有内容块（文本、工具参数、工具结果都计入） */
    public static int estimateTokens(ChatMessage message) {
        int sum = 0;
        for (ContentBlock block : message.blocks()) {
            sum += switch (block) {
                case TextBlock t -> estimateTokens(t.text());
                case ToolUseBlock tu -> estimateTokens(tu.name()) + estimateTokens(String.valueOf(tu.input()));
                case ToolResultBlock tr -> estimateTokens(tr.content());
            };
        }
        return sum;
    }

    /** 组装请求：携带完整历史与工具列表，超出窗口时从最早开始丢弃，直到总量放得下 */
    public ChatRequest buildRequest() {
        return buildRequest(tools, null);
    }

    /**
     * 组装请求：按「system → 环境 → 历史 → 轮次级」四段拼接，四段都在 trim 之外独立注入、不进历史。
     * systemPrompt 非空时首位为 SYSTEM 消息；environment 非空时紧跟一条环境 system-reminder（会话状态）；
     * turnReminder 非空时尾插为最后一条 user 消息（近因效应）。工具列表独立传递，不随历史裁剪。
     */
    public ChatRequest buildRequest(List<Tool> requestTools, ChatMessage turnReminder) {
        List<ChatMessage> requestMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestMessages.add(ChatMessage.of(ChatMessage.Role.SYSTEM, systemPrompt));
        }
        if (environment != null) {
            requestMessages.add(environment);
        }
        requestMessages.addAll(trim());
        if (turnReminder != null) {
            requestMessages.add(turnReminder);
        }
        return ChatRequest.builder()
                .model(model)
                .thinking(thinking)
                .maxTokens(maxTokens)
                .tools(requestTools)
                .messages(requestMessages)
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
        return list.stream().mapToInt(Conversation::estimateTokens).sum();
    }
}
