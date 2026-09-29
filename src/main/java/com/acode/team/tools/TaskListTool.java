package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

public final class TaskListTool extends TaskTool {
    public TaskListTool(Supplier<TeamTaskStore> store, String actor) {
        super("TaskList", "列出共享任务、状态、依赖和认领人", Permission.READ, store, actor);
    }
    @Override protected List<ParamSpec> paramSpecs() { return List.of(); }
    @Override protected Object transact(JsonNode input) { return store.get().list(); }
}

