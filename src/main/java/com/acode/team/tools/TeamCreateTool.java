package com.acode.team.tools;

import com.acode.team.Team;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.BiFunction;

public final class TeamCreateTool extends BaseTool {
    private final BiFunction<String, String, Team> create;
    public TeamCreateTool(BiFunction<String, String, Team> create) {
        super("TeamCreate", "创建会话内团队；返回实际名称，随后用 Agent 的 team_name 派遣队员。", Permission.WRITE);
        this.create = create;
    }
    @Override protected List<ParamSpec> paramSpecs() {
        return List.of(ParamSpec.required("team_name", ParamSpec.Type.STRING, "团队名称"),
                ParamSpec.optional("description", ParamSpec.Type.STRING, "团队说明"));
    }
    @Override protected ToolResult doExecute(JsonNode input, ToolContext context) {
        if (context.isSubAgent() || context.isFork()) return ToolResult.failure("队员不能管理团队");
        try { return ToolResult.success("团队 " + create.apply(input.path("team_name").textValue(), TaskTool.optional(input, "description")).name() + " 已创建"); }
        catch (RuntimeException e) { return ToolResult.failure(e.getMessage()); }
    }
}
