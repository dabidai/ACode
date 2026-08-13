package com.acode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 工具 → Anthropic tools 参数格式（name / description / input_schema）的转换器。 */
public final class ToolSchemaConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolSchemaConverter() {
    }

    /** 单个 Tool → Anthropic tools 数组元素 */
    public static ObjectNode toAnthropicTool(Tool tool) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("input_schema", tool.inputSchema());
        return node;
    }

    /** 一组 Tool → Anthropic tools 数组 */
    public static ArrayNode toAnthropicTools(List<Tool> tools) {
        ArrayNode array = JSON.createArrayNode();
        for (Tool tool : tools) {
            array.add(toAnthropicTool(tool));
        }
        return array;
    }
}
