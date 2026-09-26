package com.acode.skill;

import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Agent supplies the batch-scoped staging sink; direct calls never mutate session state. */
public final class LoadSkillTool implements Tool {
    private final SkillRuntime runtime;
    public LoadSkillTool(SkillRuntime runtime) { this.runtime = runtime; }
    public String name() { return "LoadSkill"; }
    public String description() { return "Load a named Skill from the system index when its description matches the task."; }
    public Permission permission() { return Permission.READ; }
    public JsonNode inputSchema() {
        var root = new ObjectMapper().createObjectNode();
        root.put("type", "object");
        var properties = root.putObject("properties");
        properties.putObject("name").put("type", "string");
        properties.putObject("arguments").put("type", "string");
        root.putArray("required").add("name");
        return root;
    }
    public SkillActivation prepare(JsonNode input) {
        if (input == null || !input.path("name").isTextual() || input.path("name").asText().isBlank()
                || (input.has("arguments") && !input.path("arguments").isTextual()))
            return new SkillActivation(null, "", "", -1, ToolResult.failure("LoadSkill: name required; arguments must be a string"));
        return runtime.prepare(input.path("name").asText(), input.path("arguments").asText(""));
    }
    public ToolResult execute(JsonNode input, ToolContext context) {
        return ToolResult.failure("LoadSkill requires an Agent batch context.");
    }
}
