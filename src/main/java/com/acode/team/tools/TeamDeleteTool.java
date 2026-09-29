package com.acode.team.tools;

import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Function;

public final class TeamDeleteTool extends BaseTool {
    private final Function<String, String> delete;
    public TeamDeleteTool(Function<String, String> delete) {
        super("TeamDelete", "删除全部队员均空闲且 Worktree 成果已安全保存的团队。", Permission.WRITE);
        this.delete = delete;
    }
    @Override protected List<ParamSpec> paramSpecs() { return List.of(ParamSpec.required("team_name", ParamSpec.Type.STRING, "团队名称")); }
    @Override protected ToolResult doExecute(JsonNode input, ToolContext context) {
        if (context.isSubAgent() || context.isFork()) return ToolResult.failure("队员不能管理团队");
        try { return ToolResult.success(delete.apply(input.path("team_name").textValue())); }
        catch (RuntimeException e) { return ToolResult.failure(e.getMessage()); }
    }
}
