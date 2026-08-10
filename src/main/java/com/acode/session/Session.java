package com.acode.session;

import com.acode.provider.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次会话：id（时间戳文件名）、创建时间、消息列表。
 * 无参构造 + getter/setter 供 Jackson 序列化/反序列化。
 */
public class Session {

    private String id;
    private long createdAtEpochMillis;
    private List<ChatMessage> messages = new ArrayList<>();

    public Session() {
    }

    public Session(String id, long createdAtEpochMillis, List<ChatMessage> messages) {
        this.id = id;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.messages = new ArrayList<>(messages);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public void setCreatedAtEpochMillis(long createdAtEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = new ArrayList<>(messages);
    }
}
