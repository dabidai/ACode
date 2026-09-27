package com.acode.subagent;

import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.acode.subagent.SubAgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolTest {
    @TempDir Path root;
    @TempDir Path home;
    @Test void parametersUnknownTypeSchemaAndRecursion() {
        var provider = FakeProvider.streaming("done"); var tools = new ToolRegistry();
        var tool = new AgentTool(() -> new AgentRegistry(root, home, false), () -> new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.DEFAULT), null));
        var context = new ToolContext(root);
        assertEquals("缺少参数：prompt", tool.execute(null, context).content());
        assertEquals("缺少参数：description", tool.execute(JsonNodeFactory.instance.objectNode().put("prompt", "x"), context).content());
        var input = (ObjectNode) args(); input.put("subagent_type", "missing");
        var unknown = tool.execute(input, context); assertTrue(unknown.isError()); assertTrue(unknown.content().contains("general-purpose"));
        assertFalse(context.isFork()); context.setFork(true);
        assertEquals("Fork 子 Agent 不能再创建子 Agent", tool.execute(args(), context).content());
        assertTrue(provider.receivedRequests().isEmpty());
        assertEquals(Permission.WRITE, tool.permission()); assertEquals("prompt", tool.contentField());
        assertEquals(5, tool.inputSchema().path("properties").size()); assertEquals(2, tool.inputSchema().path("required").size());
        assertFalse(tool.inputSchema().path("properties").has("isolation"));
        assertEquals(root.resolve("file"), context.resolve("file"));
    }
    @Test void unknownToolFailsBeforeProvider() throws Exception {
        var file = root.resolve(".acode/agents/bad.md"); Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: bad\ndescription: bad\ntools: [Missing]\n---\nbody");
        var registry = new AgentRegistry(root, home, false); var provider = FakeProvider.streaming("unexpected");
        var tool = new AgentTool(() -> registry, () -> new SubAgentRunner(provider, parent(), new ToolRegistry(), () -> checker(root, PermissionMode.DEFAULT), null));
        var input = (ObjectNode) args(); input.put("subagent_type", "bad");
        assertTrue(tool.execute(input, new ToolContext(root)).content().contains("引用了不存在的工具：Missing"));
        assertTrue(provider.receivedRequests().isEmpty());
    }
}
