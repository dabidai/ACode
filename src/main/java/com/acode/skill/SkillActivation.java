package com.acode.skill;

import com.acode.tool.ToolResult;

public record SkillActivation(SkillDefinition definition, String arguments, String body,
                              long generation, ToolResult result) {
    public boolean successful() { return definition != null && result.isSuccess(); }
}
