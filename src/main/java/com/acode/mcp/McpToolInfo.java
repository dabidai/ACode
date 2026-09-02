package com.acode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具发现结果：单个远端工具的元数据。inputSchema 为 MCP 声明的 JSON Schema，
 * 由适配层原样透传进 ACode 工具接口。
 */
public record McpToolInfo(String name, String description, JsonNode inputSchema) {
}
