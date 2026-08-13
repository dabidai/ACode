package com.acode.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 一条对话消息：角色 + 结构化内容块列表（text / tool_use / tool_result）。
 * <p>
 * blocks() 暴露完整结构化内容；content() 返回文本块拼接（阶段一代码兼容，
 * 不含工具块）。JSON 字段名保持 content，反序列化兼容旧版纯文本字符串。
 */
public class ChatMessage {

    public enum Role {
        SYSTEM, USER, ASSISTANT
    }

    private final Role role;
    private final List<ContentBlock> blocks;

    public ChatMessage(Role role, List<ContentBlock> blocks) {
        this.role = role;
        this.blocks = List.copyOf(blocks);
    }

    @JsonCreator
    public static ChatMessage fromJson(
            @JsonProperty("role") Role role,
            @JsonProperty("content")
            @JsonDeserialize(using = ContentBlockListDeserializer.class)
            List<ContentBlock> content) {
        return new ChatMessage(role, content);
    }

    /** 纯文本消息工厂：内部包成单个 TextBlock，保证阶段一调用零改动 */
    public static ChatMessage of(Role role, String content) {
        return new ChatMessage(role, List.of(new TextBlock(content)));
    }

    public Role role() {
        return role;
    }

    /** 结构化内容块列表（JSON 字段名为 content） */
    @JsonProperty("content")
    public List<ContentBlock> blocks() {
        return blocks;
    }

    /** 纯文本内容（拼接所有 text 块，工具块不参与）；阶段一代码兼容 */
    @JsonIgnore
    public String content() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
