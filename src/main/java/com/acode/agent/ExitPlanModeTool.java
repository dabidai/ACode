package com.acode.agent;

import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * plan 模式交付工具：模型调用后表示计划已完成，Agent 结束循环并落盘计划。
 * 仅在 plan 模式下可用；非 plan 模式调用返回错误结果。
 */
public class ExitPlanModeTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "ExitPlanMode";
    }

    @Override
    public String description() {
        return "完成计划并退出规划模式。计划正文请放在本轮回复的文本中，不要调用其他工具。";
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }

    @Override
    public JsonNode inputSchema() {
        // 无参数工具也要声明 type:"object"：Anthropic/OpenAI 均拒绝缺顶层 type 的空 schema
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        if (!context.planMode()) {
            return ToolResult.failure("只能在 plan 模式下调用");
        }
        return ToolResult.success("计划将在本轮结束后交付，请勿再调用其他工具");
    }
}
