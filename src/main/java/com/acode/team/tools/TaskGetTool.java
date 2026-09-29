package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

public final class TaskGetTool extends TaskTool {
    public TaskGetTool(Supplier<TeamTaskStore> store, String actor) {
        super("TaskGet", "查看共享任务详情", Permission.READ, store, actor);
    }
    @Override protected List<ParamSpec> paramSpecs() { return List.of(ParamSpec.required("taskID", ParamSpec.Type.STRING, "任务 ID")); }
    @Override protected Object transact(JsonNode input) { return store.get().get(input.path("taskID").textValue()); }
}

