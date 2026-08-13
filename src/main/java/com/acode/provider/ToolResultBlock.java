package com.acode.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具执行结果的回传块：关联发起时 tool_use 的 id、正文内容、是否错误。
 * JSON 字段名与 Anthropic tool_result 对齐（tool_use_id / is_error）。
 */
public record ToolResultBlock(
        @JsonProperty("tool_use_id") String toolUseId,
        String content,
        @JsonProperty("is_error") boolean isError) implements ContentBlock {
}
