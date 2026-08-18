package com.acode.prompt;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.tool.Tool;

import java.util.List;

/**
 * 每轮请求的组装入口：按「system → 环境 → 历史 → 轮次级」四段组装。
 * 环境取自 Conversation 的会话状态（每轮重新注入、不进历史）；trim 逻辑留在
 * Conversation。后续章节来源（项目指令、记忆）在此插拔，避免堆回 Conversation。
 */
public final class PromptPipeline {

    private PromptPipeline() {}

    public static ChatRequest assemble(Conversation conversation, List<Tool> tools, ChatMessage turnReminder) {
        return conversation.buildRequest(tools, turnReminder);
    }
}
