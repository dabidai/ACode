package com.acode.team.tools;

import com.acode.team.TeamTaskStore;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Shared task schema and transaction error translation. Identity is bound by the host. */
abstract class TaskTool extends BaseTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    protected final Supplier<TeamTaskStore> store;
    protected final String actor;

    TaskTool(String name, String description, Permission permission, Supplier<TeamTaskStore> store, String actor) {
        super(name, description + "。任务要追踪状态、消息是 FYI；通知队友请用 SendMessage。", permission);
        this.store = store;
        this.actor = actor;
    }

    @Override public JsonNode inputSchema() {
        ObjectNode schema = (ObjectNode) super.inputSchema();
        for (String key : List.of("addBlockedBy", "addBlocks")) {
            if (schema.path("properties").has(key))
                ((ObjectNode) schema.path("properties").path(key)).putObject("items").put("type", "string");
        }
        if (schema.path("properties").has("status"))
            ((ObjectNode) schema.path("properties").path("status")).putArray("enum").add("in_progress").add("completed");
        return schema;
    }

    @Override protected ToolResult doExecute(JsonNode input, ToolContext context) {
        try { return ToolResult.success(JSON.writeValueAsString(transact(input == null ? JSON.createObjectNode() : input))); }
        catch (Exception e) { return ToolResult.failure(e.getMessage()); }
    }

    protected abstract Object transact(JsonNode input);

    static String optional(JsonNode input, String key) {
        return input.hasNonNull(key) ? input.path(key).textValue() : null;
    }

    static List<String> ids(JsonNode input, String key) {
        var ids = new ArrayList<String>();
        if (!input.hasNonNull(key)) return ids;
        for (JsonNode item : input.path(key)) {
            if (!item.isTextual() || item.textValue().isBlank())
                throw new IllegalArgumentException("参数 " + key + " 必须为非空任务 ID 字符串数组");
            ids.add(item.textValue());
        }
        return ids;
    }
}
