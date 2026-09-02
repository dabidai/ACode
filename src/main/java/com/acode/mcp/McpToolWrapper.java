package com.acode.mcp;

import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 把 MCP 远端工具包装成 ACode Tool 接口（直接实现接口、不继承 BaseTool——嵌套 schema 需原样透传、
 * 超时由连接层保证，BaseTool 的 final 模板方法形状不符）。
 * 注册名 = {@code server名_工具名}（仅 Agent 可见）；内部持原始工具名，调远端时不带前缀。
 * 任何异常转 {@link ToolResult#failure}，不向上抛。
 */
public class McpToolWrapper implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String description;
    private final Permission permission;
    private final JsonNode inputSchema;
    private final McpServerConnection connection;

    McpToolWrapper(String serverName, McpToolInfo info, Permission permission, McpServerConnection connection) {
        this.serverName = serverName;
        this.toolName = info.name();
        this.description = info.description() == null || info.description().isBlank()
                ? "（来源：MCP server " + serverName + "）"
                : info.description() + "（来源：MCP server " + serverName + "）";
        this.permission = permission;
        this.inputSchema = info.inputSchema() != null ? info.inputSchema() : defaultSchema();
        this.connection = connection;
    }

    @Override
    public String name() {
        return serverName + "_" + toolName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Permission permission() {
        return permission;
    }

    /** 原样透传 MCP 声明的 inputSchema（含嵌套层级，进 Agent 请求不丢结构） */
    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            JsonNode result = connection.callTool(toolName, input);
            return ToolResult.success(extractText(result));
        } catch (McpException e) {
            return ToolResult.failure(e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure("MCP 工具调用异常：" + e.getMessage());
        }
    }

    private static String extractText(JsonNode result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private static ObjectNode defaultSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        return schema;
    }
}
