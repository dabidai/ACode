package com.acode.skill;

import com.acode.provider.ChatMessage;
import com.acode.tool.ToolResult;
import java.util.List;

/** Stage twelve owns isolation, context selection and child execution. */
@FunctionalInterface
public interface SkillForkHost {
    ToolResult execute(SkillDefinition definition, String arguments, List<ChatMessage> history);
}
