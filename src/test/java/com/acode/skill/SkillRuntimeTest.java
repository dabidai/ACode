package com.acode.skill;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.acode.skill.SkillRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillRuntimeTest {
    @TempDir Path root;
    @Test void lateRegisteredToolsAndReloadDoNotMutateActiveSnapshot() throws Exception {
        Path file = write(root, "a.md", definition("a", "allowedTools: [mcp_demo_ReadFile]", "old"));
        var repo = new SkillRepository(root, null, s -> {}); repo.reload();
        var tools = new ToolRegistry();
        var runtime = new SkillRuntime(repo, tools, new Conversation("default", false, 1000, 10000));
        assertFalse(runtime.prepare("a", null).successful());
        tools.register(new Tool() {
            public String name() { return "mcp_demo_ReadFile"; }
            public String description() { return "MCP tool"; }
            public Permission permission() { return Permission.READ; }
            public com.fasterxml.jackson.databind.JsonNode inputSchema() { return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(); }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext ctx) { return ToolResult.success("ok"); }
        });
        var staged = runtime.prepare("a", null); assertTrue(staged.successful());
        Files.writeString(file, definition("a", "model: changed", "new")); repo.reload();
        assertTrue(runtime.commit(staged).endsWith("old"));
        assertEquals(Set.of("mcp_demo_ReadFile"), runtime.snapshot().allowed());
        assertTrue(runtime.commit(runtime.prepare("a", null)).endsWith("new"));
        assertNull(runtime.snapshot().allowed()); assertEquals("changed", runtime.snapshot().model());
        tools.disable("mcp_demo_ReadFile");
        Files.writeString(file, definition("a", "allowedTools: [mcp_demo_ReadFile]", "disabled"));
        assertFalse(runtime.prepare("a", null).successful());
    }
    @Test void intersectionsModelsVersionsFailuresAndLifecycle() throws Exception {
        Path file = write(root, "a.md", definition("a", "allowedTools: [ReadFile, Grep]\nmodel: first", "a\n$ARGUMENTS"));
        write(root, "b.md", definition("b", "allowedTools: [Grep, Glob]\nmodel: second", "b"));
        write(root, "empty.md", definition("empty", "", "empty"));
        write(root, "bad.md", definition("bad", "allowedTools: [Missing]", "bad"));
        write(root, "fork.md", definition("fork", "mode: fork", "fork"));
        var repo = new SkillRepository(root, null, s -> {}); repo.reload();
        var tools = new ToolRegistry(); DefaultToolset.registerAll(tools);
        var conversation = new Conversation("default", false, 1000, 10000);
        var runtime = new SkillRuntime(repo, tools, conversation);
        var a = runtime.prepare("a", "x"); assertTrue(runtime.commit(a).contains("x"));
        assertNull(runtime.commit(runtime.prepare("a", "x")));
        assertTrue(runtime.commit(runtime.prepare("a", "y")).contains("取代旧版本"));
        runtime.commit(runtime.prepare("b", null)); runtime.commit(runtime.prepare("empty", null));
        assertEquals(Set.of("Grep"), runtime.snapshot().allowed()); assertTrue(runtime.snapshot().allows("LoadSkill"));
        assertEquals("second", runtime.snapshot().model());
        assertFalse(runtime.prepare("bad", null).successful());
        assertTrue(runtime.prepare("fork", null).result().content().contains("阶段十二"));
        tools.disable("ReadFile"); assertFalse(runtime.prepare("a", "x").successful()); tools.enable("ReadFile");
        assertEquals("second", runtime.snapshot().model());
        Files.writeString(file, definition("a", "allowedTools: [Glob]\nmodel: third", "new"));
        assertEquals("a\nx", a.body()); assertEquals("second", runtime.snapshot().model());
        runtime.commit(runtime.prepare("a", null)); assertEquals("third", runtime.snapshot().model());
        assertEquals(Set.of("Glob"), runtime.snapshot().allowed());
        var pending = runtime.prepare("b", null);
        conversation.replaceAll(List.of(ChatMessage.of(ChatMessage.Role.USER, "summary")));
        assertNull(runtime.commit(pending)); assertTrue(runtime.activeNames().isEmpty());
        assertNull(runtime.snapshot().model()); assertTrue(runtime.drainReminder().contains("已失效，如需继续请重新加载"));
        assertNull(runtime.drainReminder());
        runtime.commitToHistory(runtime.prepare("a", null), conversation, conversation.currentEpoch());
        assertEquals(2, conversation.history().size());
        conversation.clear(); assertTrue(runtime.activeNames().isEmpty()); assertNull(runtime.snapshot().allowed());
        runtime.commitToHistory(runtime.prepare("a", null), conversation, conversation.currentEpoch() - 1);
        assertTrue(runtime.activeNames().isEmpty()); assertTrue(conversation.history().isEmpty());
    }
}
