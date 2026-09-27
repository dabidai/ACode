package com.acode;

import com.acode.config.AppConfig;
import com.acode.permission.PermissionResponse;
import com.acode.provider.*;
import com.acode.ui.OutputPane;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolIntegrationTest {
    @TempDir Path root;
    private String oldHome;
    @BeforeEach void isolate() throws Exception {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory(Path.of("target").toAbsolutePath(), "ch12-home-").toString());
    }
    @AfterEach void restore() { System.setProperty("user.home", oldHome); }
    ConversationController controller(ChatProvider provider, OutputPane output) {
        var config = new AppConfig(); config.setProtocol("anthropic"); config.setModel("test");
        config.setMemoryAuto(false); config.setMaxContextTokens(20000); config.setMaxIterations(5);
        var c = new ConversationController(provider, config, false);
        c.setProjectRoot(root); c.setOutput(output); c.setScreenWriter(new StringWriter()); return c;
    }
    @Test void controllerDispatchWarningAndPlanBoundary() throws Exception {
        var file = root.resolve(".acode/agents/bad.md"); Files.createDirectories(file.getParent()); Files.writeString(file, "---\ndescription: broken\n---\n");
        var input = JsonNodeFactory.instance.objectNode().put("prompt", "read").put("description", "read").put("subagent_type", "Explore");
        var provider = scripted(List.of(List.of(toolUse("a", "Agent", input), complete()), List.of(delta("PRIVATE"), complete()), List.of(delta("parent answer"), complete())));
        var output = new OutputPane(); var c = controller(provider, output);
        try {
            c.setConfirmAnswerer(event -> PermissionResponse.ALLOW);
            c.connectMcp(); c.initSkills(); c.initSessionState();
            c.handleExchange("dispatch", () -> false, () -> {});
            assertEquals(3, provider.receivedRequests().size());
            assertTrue(provider.receivedRequests().getFirst().tools().stream().anyMatch(t -> t.name().equals("Agent")));
            String rendered = String.join("\n", output.lines());
            assertTrue(rendered.contains("bad.md")); assertFalse(rendered.contains("PRIVATE")); assertTrue(rendered.contains("完成子任务"));
            c.commandProcessor().handleLine("/plan");
            c.handleExchange("plan", () -> false, () -> {});
            assertFalse(provider.receivedRequests().getLast().tools().stream().anyMatch(t -> t.name().equals("Agent")));
        } finally { c.closeSession(); }
    }
    @Test void rejectedDispatchDoesNotStartChild() {
        var input = JsonNodeFactory.instance.objectNode().put("prompt", "write").put("description", "write");
        var provider = scripted(List.of(List.of(toolUse("a", "Agent", input), complete()), List.of(delta("rejected"), complete())));
        var c = controller(provider, new OutputPane());
        try {
            c.setConfirmAnswerer(event -> PermissionResponse.DENY);
            c.handleExchange("dispatch", () -> false, () -> {});
            assertEquals(2, provider.receivedRequests().size());
            assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(b -> b instanceof ToolResultBlock r && r.isError()));
        } finally { c.closeSession(); }
    }
    private void writeSkill(String name, String metadata, String body) throws Exception {
        var path = root.resolve(".acode/skills/" + name + ".md"); Files.createDirectories(path.getParent());
        Files.writeString(path, "---\nname: " + name + "\ndescription: local\n" + metadata + "\n---\n" + body);
    }
    @Test void activeSkillModelAndToolCeilingSurviveActualAgentDispatch() throws Exception {
        writeSkill("limit", "allowedTools: [Agent, ReadFile]\nmodel: selected-model", "Keep scope");
        var json = JsonNodeFactory.instance;
        var dispatch = json.objectNode().put("prompt", "task").put("description", "task").put("model", "ignored-on-fork");
        var provider = scripted(List.of(
                List.of(delta("skill active"), complete()),
                List.of(toolUse("dispatch", "Agent", dispatch), complete()),
                List.of(toolUse("forbidden", "WriteFile", json.objectNode().put("file_path", "escape.txt").put("content", "escape")), complete()),
                List.of(delta("child done"), complete()), List.of(delta("parent done"), complete())));
        var c = controller(provider, new OutputPane());
        var confirmations = new java.util.concurrent.atomic.AtomicInteger();
        try {
            c.setConfirmAnswerer(event -> { confirmations.incrementAndGet(); return PermissionResponse.ALLOW; });
            c.initSkills(); c.commandProcessor().handleLine("/limit");
            c.handleExchange("dispatch", () -> false, () -> {});
            assertEquals(5, provider.receivedRequests().size());
            var child = provider.receivedRequests().get(2);
            assertEquals("selected-model", child.model());
            assertFalse(child.tools().stream().anyMatch(t -> t.name().equals("WriteFile")));
            assertTrue(provider.receivedRequests().get(3).messages().stream().flatMap(m -> m.blocks().stream())
                    .anyMatch(b -> b instanceof ToolResultBlock r && r.isError()));
            assertEquals(1, confirmations.get()); assertFalse(Files.exists(root.resolve("escape.txt")));
        } finally { c.closeSession(); }
    }
    @Test void slashForkSkillInPlanCannotAdvertiseOrExecuteWriteTools() throws Exception {
        writeSkill("fork", "mode: fork\ncontext: none", "try writing");
        var input = JsonNodeFactory.instance.objectNode().put("file_path", "forbidden.txt").put("content", "no");
        var provider = scripted(List.of(List.of(toolUse("w", "WriteFile", input), complete()), List.of(delta("denied"), complete())));
        var c = controller(provider, new OutputPane());
        try {
            c.permissionChecker().setMode(com.acode.permission.PermissionMode.BYPASS);
            c.initSkills(); c.commandProcessor().handleLine("/plan"); c.commandProcessor().handleLine("/fork");
            assertEquals(2, provider.receivedRequests().size());
            assertTrue(provider.receivedRequests().getFirst().tools().stream().allMatch(t -> t.permission() == com.acode.tool.Permission.READ));
            assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream())
                    .anyMatch(b -> b instanceof ToolResultBlock r && r.isError()));
            assertFalse(Files.exists(root.resolve("forbidden.txt")));
        } finally { c.closeSession(); }
    }
    @Test void childProviderFailureIsReturnedAndParentCanContinue() {
        var input = JsonNodeFactory.instance.objectNode().put("prompt", "task").put("description", "task");
        var provider = scripted(List.of(List.of(toolUse("a", "Agent", input), complete()),
                List.of(delta("unfinished private text"), error(new InvalidRequestException("child rejected"))),
                List.of(delta("alternative approach"), complete()), List.of(delta("next exchange"), complete())));
        var output = new OutputPane(); var c = controller(provider, output);
        try {
            c.setConfirmAnswerer(event -> PermissionResponse.ALLOW);
            c.handleExchange("dispatch", () -> false, () -> {});
            assertTrue(provider.receivedRequests().get(2).messages().stream().flatMap(m -> m.blocks().stream())
                    .anyMatch(b -> b instanceof ToolResultBlock r && r.isError() && r.content().contains("child rejected")));
            c.handleExchange("continue", () -> false, () -> {});
            assertEquals(4, provider.receivedRequests().size());
            assertFalse(String.join("\n", output.lines()).contains("unfinished private text"));
            assertTrue(String.join("\n", output.lines()).contains("next exchange"));
        } finally { c.closeSession(); }
    }
    @Test void cancellingNestedDispatchAllowsNextExchange() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            var entered = new java.util.concurrent.CountDownLatch(1); var stopped = new java.util.concurrent.CountDownLatch(1);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var input = JsonNodeFactory.instance.objectNode().put("prompt", "task").put("description", "task");
            ChatProvider provider = (request, listener) -> {
                switch (calls.getAndIncrement()) {
                    case 0 -> { listener.onToolUse(new ToolUseBlock("dispatch", "Agent", input)); listener.onComplete(); }
                    case 1 -> {
                        entered.countDown();
                        try { new java.util.concurrent.CountDownLatch(1).await(); }
                        catch (InterruptedException e) { stopped.countDown(); Thread.currentThread().interrupt(); }
                        listener.onDelta("LATE CHILD OUTPUT"); listener.onComplete();
                    }
                    default -> { listener.onDelta("recovered exchange"); listener.onComplete(); }
                }
            };
            var output = new OutputPane(); var c = controller(provider, output);
            try {
                c.setConfirmAnswerer(event -> PermissionResponse.ALLOW);
                c.handleExchange("dispatch", () -> entered.getCount() == 0, () -> {});
                assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS));
                c.handleExchange("continue", () -> false, () -> {});
                assertEquals(3, calls.get());
                assertFalse(String.join("\n", output.lines()).contains("LATE CHILD OUTPUT"));
                assertTrue(String.join("\n", output.lines()).contains("recovered exchange"));
            } finally { c.closeSession(); }
        });
    }
}
