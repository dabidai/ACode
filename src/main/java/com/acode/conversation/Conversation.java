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

    /** 清空全部消息历史（/clear 用）。 */
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
     * 组装请求：显式指定工具列表，并在 trim() 结果之前插入 system 提醒（不进历史）。
     * systemReminder 为 null 时不插入；工具列表在 trim 之外独立传递，不随历史裁剪。
     */
    public ChatRequest buildRequest(List<Tool> requestTools, ChatMessage systemReminder) {
        List<ChatMessage> requestMessages = trim();
        if (systemReminder != null) {
            requestMessages = new ArrayList<>(requestMessages);
            requestMessages.add(0, systemReminder);
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
