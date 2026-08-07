package com.acode.provider;

/**
 * 一条对话消息。record 形式，天然不可变。
 */
public record ChatMessage(Role role, String content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    public static ChatMessage of(Role role, String content) {
        return new ChatMessage(role, content);
    }
}
