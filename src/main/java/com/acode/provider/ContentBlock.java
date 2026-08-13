package com.acode.provider;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息内容块：结构化消息模型的地基。
 * 序列化时按 type 字段区分：text / tool_use / tool_result，与 Anthropic content block 对齐。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
})
public sealed interface ContentBlock permits TextBlock, ToolUseBlock, ToolResultBlock {
}
