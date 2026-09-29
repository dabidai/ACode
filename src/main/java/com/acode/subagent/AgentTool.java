package com.acode.subagent;

import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.function.Supplier;

public final class AgentTool implements Tool {
    private final Supplier<AgentRegistry> definitions;
    private final Supplier<SubAgentRunner> runner;
    private Supplier<com.acode.team.TeamSession> teams;
    public AgentTool(Supplier<AgentRegistry> definitions, Supplier<SubAgentRunner> runner,
                     Supplier<com.acode.team.TeamSession> teams) {
        this(definitions, runner); this.teams = teams;
    }
    public AgentTool(Supplier<AgentRegistry> definitions, Supplier<SubAgentRunner> runner) {
        this.definitions = definitions; this.runner = runner;
    }
    public String name() { return "Agent"; }
    public Permission permission() { return Permission.WRITE; }
    public String contentField() { return "prompt"; }
    public String description() {
        return "派一个子 Agent 在独立的干净上下文里完成子任务，完成后只把结果返回，中间过程不进主对话。prompt 写任务说明；description 写这次派活的一句话说明；subagent_type 指定预定义 Agent 类型（如 Explore、Plan、general-purpose），留空则 Fork 一个继承当前对话历史的临时助手。固定角色、固定职责的子任务（探索、规划、审查）指定 subagent_type；与当前对话高度相关的临时任务留空走 Fork。";
    }
    public JsonNode inputSchema() {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        var properties = schema.putObject("properties");
        for (String key : List.of("prompt", "description", "subagent_type", "model", "name")) properties.putObject(key).put("type", "string");
        if (teams != null) {
            properties.putObject("team_name").put("type", "string").put("description", "派为该团队的长期队员；必须同时提供 name");
            properties.putObject("plan_mode_required").put("type", "boolean");
        }
        schema.putArray("required").add("prompt").add("description");
        return schema;
    }
    public ToolResult execute(JsonNode input, ToolContext context) {
        if (context.isFork()) return ToolResult.failure("Fork 子 Agent 不能再创建子 Agent");
        if (context.isSubAgent()) return ToolResult.failure("子 Agent 不能再创建子 Agent");
        for (String key : List.of("prompt", "description"))
            if (input == null || !input.path(key).isTextual() || input.path(key).asText().isBlank()) return ToolResult.failure("缺少参数：" + key);
        for (String key : List.of("subagent_type", "model", "name", "team_name"))
            if (input.has(key) && !input.path(key).isTextual()) return ToolResult.failure("参数必须是字符串：" + key);
        var registry = definitions.get();
        String type = input.path("subagent_type").asText("");
        AgentDefinition definition = type.isBlank() ? null : registry.get(type);
        if (!type.isBlank() && definition == null) return ToolResult.failure("未知的 Agent 类型：" + type + "（可用类型：" + String.join("、", registry.names()) + "）");
        if (input.has("team_name")) {
            if (teams == null) return ToolResult.failure("当前上下文不支持团队派生");
            if (!input.path("name").isTextual() || input.path("name").asText().isBlank()) return ToolResult.failure("缺少参数：name");
            if (input.has("plan_mode_required") && !input.path("plan_mode_required").isBoolean())
                return ToolResult.failure("参数必须是布尔值：plan_mode_required");
            return teams.get().spawn(input.path("team_name").asText(), input.path("name").asText(),
                    input.path("prompt").asText(), definition, input.path("model").asText(null), input.path("plan_mode_required").asBoolean(false));
        }
        String label = input.path("name").asText("");
        if (label.isBlank()) label = input.path("description").asText();
        return runner.get().run(definition, input.path("prompt").asText(), label, input.path("model").asText(null), context.workingDirectory());
    }
}
