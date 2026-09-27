package com.acode.subagent;

import com.acode.permission.PermissionMode;
import java.util.List;

public record AgentDefinition(String agentType, String whenToUse, List<String> tools,
        List<String> disallowedTools, String model, int maxTurns, PermissionMode permissionMode,
        String systemPrompt, String filePath, String source) {
    public AgentDefinition {
        tools = List.copyOf(tools);
        disallowedTools = List.copyOf(disallowedTools);
        if (maxTurns < 1) throw new IllegalArgumentException("maxTurns must be positive");
    }
}
