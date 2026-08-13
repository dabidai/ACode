package com.acode.provider;

import com.acode.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次流式对话请求。
 *
 * @param messages 完整对话历史（含本次用户消息）
 * @param model    模型名称
 * @param thinking 是否开启 extended thinking（仅 Claude 端生效）
 * @param maxTokens 生成 token 上限
 * @param tools    可选：模型可调用的工具列表，为空则请求不含 tools 参数
 */
public class ChatRequest {

    public static final int DEFAULT_MAX_TOKENS = 4096;

    private final List<ChatMessage> messages;
    private final String model;
    private final boolean thinking;
    private final int maxTokens;
    private final List<Tool> tools;

    private ChatRequest(Builder builder) {
        this.messages = List.copyOf(builder.messages);
        this.model = builder.model;
        this.thinking = builder.thinking;
        this.maxTokens = builder.maxTokens;
        this.tools = List.copyOf(builder.tools);
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public String model() {
        return model;
    }

    public boolean thinking() {
        return thinking;
    }

    public int maxTokens() {
        return maxTokens;
    }

    /** 模型可调用的工具列表；未设置时为空 */
    public List<Tool> tools() {
        return tools;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<Tool> tools = new ArrayList<>();
        private String model;
        private boolean thinking;
        private int maxTokens = DEFAULT_MAX_TOKENS;

        public Builder message(ChatMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.addAll(messages);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder thinking(boolean thinking) {
            this.thinking = thinking;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.tools.addAll(tools);
            return this;
        }

        public ChatRequest build() {
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("ChatRequest: model 不能为空");
            }
            if (messages.isEmpty()) {
                throw new IllegalStateException("ChatRequest: messages 不能为空");
            }
            return new ChatRequest(this);
        }
    }
}
