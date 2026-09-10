package com.acode.session;

import com.acode.provider.ChatMessage;

import java.util.List;

/**
 * 一次会话：id（即文件名）、最后活跃时间（Unix 秒，取会话文件末行的落盘时刻）、消息列表，
 * 以及"是否已过期"（仅用于列表渲染与排序，不触发任何删除）。
 */
public record Session(String id, long lastActiveEpochSeconds, List<ChatMessage> messages,
                      boolean expired) {

    public Session {
        messages = List.copyOf(messages);
    }

    public Session(String id, long lastActiveEpochSeconds, List<ChatMessage> messages) {
        this(id, lastActiveEpochSeconds, messages, false);
    }
}
