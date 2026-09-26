package com.acode.skill;

import java.util.List;

/** A parsed immutable definition; body stays outside system instructions. */
public record SkillDefinition(String name, String description, List<String> allowedTools,
                              String model, String mode, String context, String body,
                              SkillSource source) {
    public SkillDefinition { allowedTools = List.copyOf(allowedTools); }

    public String render(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return body.replaceAll("(?m)^.*\\$ARGUMENTS.*(?:\\R|$)", "");
        }
        return body.replace("$ARGUMENTS", arguments);
    }
}
