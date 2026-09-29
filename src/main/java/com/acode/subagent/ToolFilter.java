package com.acode.subagent;

import com.acode.tool.*;
import java.util.*;

public final class ToolFilter {
    public static final Set<String> BACKGROUND_TOOLS = Set.of("ReadFile", "Glob", "Grep", "WriteFile", "EditFile");
    private ToolFilter() {}
    public static List<Tool> filter(ToolRegistry registry, AgentDefinition definition, boolean fork) {
        if (definition != null) {
            var references = new ArrayList<>(definition.tools());
            references.addAll(definition.disallowedTools());
            for (String name : references) if (registry.get(name) == null)
                throw new IllegalArgumentException("Agent 定义「" + definition.agentType() + "」引用了不存在的工具：" + name + "（请检查定义文件的 tools/disallowedTools）");
        }
        return registry.availableList().stream()
                .filter(t -> !Set.of("AskUser", "ExitPlanMode").contains(t.name()))
                // Team tools bind the Lead identity; children receive separately bound tools only.
                .filter(t -> !Set.of("TeamCreate", "TeamDelete", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage").contains(t.name()))
                .filter(t -> fork || !t.name().equals("Agent"))
                .filter(t -> definition == null || !definition.disallowedTools().contains(t.name()))
                .filter(t -> definition == null || definition.tools().isEmpty() || definition.tools().contains(t.name()))
                .toList();
    }
}
