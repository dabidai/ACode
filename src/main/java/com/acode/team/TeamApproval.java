package com.acode.team;

import com.acode.provider.ToolUseBlock;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;

/** Exact tool/input grants; a reply never enables an entire tool or bypasses parent DENY. */
final class TeamApproval {
    private volatile List<JsonNode> grants = List.of();
    private static final Set<String> COORDINATION = Set.of("TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage");
    static boolean coordination(String name) { return COORDINATION.contains(name); }
    void reply(String message) {
        try {
            JsonNode reply = new ObjectMapper().readTree(message);
            if (reply == null || !reply.path("approved").isBoolean()) throw new IllegalArgumentException("审批回复需要 approved 布尔值");
            if (!reply.path("approved").booleanValue()) { grants = List.of(); return; }
            if (!reply.path("operations").isArray()) throw new IllegalArgumentException("审批通过需要 operations 数组");
            var parsed = new java.util.ArrayList<JsonNode>();
            for (JsonNode operation : reply.path("operations")) {
                if (!operation.path("tool").isTextual() || !operation.path("input").isObject())
                    throw new IllegalArgumentException("审批操作需要 tool 和 input");
                parsed.add(operation.deepCopy());
            }
            grants = List.copyOf(parsed);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("审批回复必须为 JSON", e); }
    }
    boolean permits(ToolUseBlock call) {
        return grants.stream().anyMatch(g -> g.path("tool").textValue().equals(call.name()) && g.path("input").equals(call.input()));
    }
    boolean exposes(Tool tool) {
        return tool.permission() == Permission.READ || coordination(tool.name())
                || grants.stream().anyMatch(g -> g.path("tool").textValue().equals(tool.name()));
    }
    Tool guard(Tool delegate) {
        return new Tool() {
            public String name() { return delegate.name(); }
            public String description() { return delegate.description(); }
            public Permission permission() { return delegate.permission(); }
            public String contentField() { return delegate.contentField(); }
            public JsonNode inputSchema() { return delegate.inputSchema(); }
            public ToolResult execute(JsonNode input, ToolContext context) {
                if (permission() != Permission.READ && !coordination(name()) && !permits(new ToolUseBlock("", name(), input)))
                    return ToolResult.failure("操作未获 Lead 明确审批");
                return delegate.execute(input, context);
            }
        };
    }
}
