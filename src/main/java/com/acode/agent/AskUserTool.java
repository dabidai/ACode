package com.acode.agent;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 选择工具：模型发起 question + options 的单选菜单，用户选完结果回传模型。
 * 走 InteractiveTool.executeInteractive（经事件队列握手，UI 主线程弹菜单应答）；
 * 不用 BaseTool——其 execute 为 final 带 10s 超时（会误杀等待用户）、inputSchema 把 ARRAY items
 * 硬编码成 object（无法表达 string 数组）。Permission.READ：绕过确认门、自动进 plan 模式工具表。
 */
public class AskUserTool implements Tool, InteractiveTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "AskUser";
    }

    @Override
    public String description() {
        return "向用户发起单选选择：给出 question 与若干选项，用户用 ↑↓ 菜单选中一项并回传结果。"
                + "适合在两三个选项间需用户拍板时使用；不适合让用户输入自由文本或长篇回复。";
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }

    @Override
    public JsonNode inputSchema() {
        // options 为 string 数组；逐节点显式构造（Jackson ObjectNode.set 返回泛型 T，
        // 链式调用会退化推断成 JsonNode 导致下一环 .set 不可见）
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("question").put("type", "string");
        ObjectNode options = properties.putObject("options");
        options.put("type", "array");
        options.putObject("items").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("question");
        required.add("options");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        // 生产路径只走 executeInteractive；防御性兜底
        return ToolResult.failure("AskUser 须以交互模式执行");
    }

    @Override
    public ToolResult executeInteractive(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
        List<String> options = stringList(call.input().path("options"));
        if (options.isEmpty()) {
            return ToolResult.failure("选项不能为空");
        }
        String question = call.input().path("question").asText("");
        Choice choice = new Choice();
        AgentEvent.putSafe(events, new ChoiceRequestEvent(call.id(), call.name(), question, options, choice));
        String selected = choice.await(cancelled);
        if (selected == null) {
            return ToolResult.failure("用户取消选择");
        }
        return ToolResult.success(selected);
    }

    /** options 须为非空字符串数组；空/缺失/含非字符串元素时返回空表（触发「选项不能为空」）。 */
    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return List.of();
            }
            list.add(item.asText());
        }
        return list;
    }
}
