package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

public final class TaskCreateTool extends TaskTool {
    public TaskCreateTool(Supplier<TeamTaskStore> store, String actor) {
        super("TaskCreate", "创建共享任务", Permission.WRITE, store, actor);
    }
    @Override protected List<ParamSpec> paramSpecs() { return List.of(ParamSpec.required("title", ParamSpec.Type.STRING, "任务标题"), ParamSpec.optional("description", ParamSpec.Type.STRING, "任务描述"), ParamSpec.optional("addBlockedBy", ParamSpec.Type.ARRAY, "前置任务 ID"), ParamSpec.optional("addBlocks", ParamSpec.Type.ARRAY, "后续任务 ID")); }
    @Override protected Object transact(JsonNode input) { return store.get().create(input.path("title").textValue(), optional(input, "description"), ids(input, "addBlockedBy"), ids(input, "addBlocks")); }
}

