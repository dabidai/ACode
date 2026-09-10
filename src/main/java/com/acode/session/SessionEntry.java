package com.acode.session;

import com.acode.provider.ChatMessage;

/** JSONL 的一行：一条消息 + 落盘时刻（Unix 秒） */
public record SessionEntry(ChatMessage message, long ts) {
}
