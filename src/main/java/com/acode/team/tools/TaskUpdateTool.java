package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

public final class TaskUpdateTool extends TaskTool {
    public TaskUpdateTool(Supplier<TeamTaskStore> store, String actor) {
        super("TaskUpdate", "认领、完成共享任务或增加依赖", Permission.WRITE, store, actor);
    }
    @Override protected List<ParamSpec> paramSpecs() { return List.of(ParamSpec.required("taskID", ParamSpec.Type.STRING, "任务 ID"), ParamSpec.optional("status", ParamSpec.Type.STRING, "in_progress 或 completed"), ParamSpec.optional("addBlockedBy", ParamSpec.Type.ARRAY, "前置任务 ID"), ParamSpec.optional("addBlocks", ParamSpec.Type.ARRAY, "后续任务 ID")); }
    @Override protected Object transact(JsonNode input) { return store.get().update(input.path("taskID").textValue(), actor, optional(input, "status"), ids(input, "addBlockedBy"), ids(input, "addBlocks")); }
}

