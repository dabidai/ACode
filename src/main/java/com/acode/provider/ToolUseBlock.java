package com.acode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 模型发起的工具调用：唯一 id、工具名、解析后的参数 JSON。
 * input 序列化时作为对象节点，可被 Anthropic tool_use 直接复用。
 */
public record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {
}
