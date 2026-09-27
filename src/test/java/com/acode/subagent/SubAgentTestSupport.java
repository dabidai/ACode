package com.acode.subagent;

import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class SubAgentTestSupport {
    static JsonNode args() { return JsonNodeFactory.instance.objectNode().put("prompt", "task").put("description", "test"); }
    static Conversation parent() {
        var result = new Conversation("parent-model", true, 1234, 8000);
        result.setSystemPrompt("PARENT SYSTEM");
        result.setEnvironment(ChatMessage.of(ChatMessage.Role.USER, "ENVIRONMENT"));
        result.addMessage(ChatMessage.of(ChatMessage.Role.USER, "PARENT SECRET"));
        return result;
    }
    static PermissionChecker checker(Path root, PermissionMode mode) {
        return new PermissionChecker(mode, root, new RuleEngine(root.resolve("user.yaml"), root.resolve("project.yaml"), root.resolve("local.yaml")));
    }
    static AgentDefinition definition(List<String> allow, List<String> deny, int turns, PermissionMode mode) {
        return new AgentDefinition("test", "test", allow, deny, "inherit", turns, mode, "CHILD SYSTEM", "test.md", "project");
    }
    static Tool tool(String name, Permission permission, AtomicInteger count) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return name; }
            public Permission permission() { return permission; }
            public String contentField() { return name.equals("Bash") ? "command" : "file_path"; }
            public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            public ToolResult execute(JsonNode input, ToolContext context) { count.incrementAndGet(); return ToolResult.success("TOOL RESULT"); }
        };
    }
}
