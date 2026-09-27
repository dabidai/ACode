package com.acode.subagent;

import com.acode.agent.*;
import com.acode.hook.*;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.skill.*;
import com.acode.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.acode.subagent.SubAgentTestSupport.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentAdaptersTest {
    @TempDir Path root;
    @TempDir Path home;
    @Test void skillContextsModelAndNoParentActivation() throws Exception {
        for (String context : List.of("none", "recent", "full")) {
            var parent = parent(); for (int i=0;i<10;i++) parent.addMessage(ChatMessage.of(ChatMessage.Role.USER, "HISTORY " + i));
            var tools = new ToolRegistry(); var provider = streaming("skill result");
            var runner = new SubAgentRunner(provider, parent, tools, () -> checker(root, PermissionMode.DEFAULT), null);
            var skill = new SkillDefinition("test", "test", List.of(), "selected", "fork", context, "body $ARGUMENTS", SkillSource.file("project", root.resolve("skill.md")));
            var result = new SkillForkAdapter(() -> runner, () -> root).execute(skill, "args", parent.history());
            assertEquals("skill result", result.content());
            var request = provider.receivedRequests().getFirst(); assertEquals("selected", request.model());
            assertEquals(context.equals("none") ? 3 : context.equals("recent") ? 8 : 14, request.messages().size());
            assertTrue(request.messages().getLast().content().endsWith("body args")); assertEquals(11, parent.messageCount());
        }
        var path = root.resolve(".acode/skills/fork.md"); Files.createDirectories(path.getParent());
        Files.writeString(path, "---\nname: fork\ndescription: fork\nmode: fork\n---\nbody");
        var repository = new SkillRepository(root, home, ignored -> {}); repository.reload();
        var conversation = parent(); var runtime = new SkillRuntime(repository, new ToolRegistry(), conversation);
        runtime.setForkHost((d, a, h) -> ToolResult.success("done"));
        var candidate = runtime.prepare("fork", ""); assertTrue(candidate.result().isSuccess());
        assertNull(runtime.commit(candidate)); assertTrue(runtime.activeNames().isEmpty());
        runtime.setForkHost((d, a, h) -> ToolResult.failure("broken")); assertTrue(runtime.prepare("fork", "").result().isError());
    }
    @Test void hookCallsSameRunnerAndPropagatesFailureWithoutDeadlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var hook = new HookConfig("agent", HookEvents.TURN_START, c -> true,
                    new HookConfig.Action(HookConfig.ActionType.AGENT, Map.of("prompt", "review"), Duration.ofSeconds(5)), false, false, false);
            var reference = new AtomicReference<HookEngine>(); var provider = streaming("review done");
            var runner = new SubAgentRunner(provider, parent(), new ToolRegistry(), () -> checker(root, PermissionMode.DEFAULT), reference::get);
            var actions = new HookActions(root, prompt -> runner.run(definition(List.of(), List.of(), 20, PermissionMode.DEFAULT), prompt, "hook", null, root));
            try (var engine = new HookEngine(List.of(hook), actions, () -> checker(root, PermissionMode.DEFAULT), null)) {
                reference.set(engine); engine.fire(HookEvents.TURN_START, HookContext.lifecycle(HookEvents.TURN_START, "task"));
                assertEquals(1, provider.receivedRequests().size(), "child hooks cannot recursively spawn");
            }
            assertFalse(new HookActions(root, p -> ToolResult.failure("failed")).execute(hook, HookContext.lifecycle(HookEvents.TURN_START, "task")).ok());
        });
    }
    @Test void childToolsTriggerHooksButPromptsDoNotLeak() {
        var calls = new AtomicInteger(); var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, calls));
        var hook = new HookConfig("prompt", HookEvents.PRE_TOOL_USE, c -> true,
                new HookConfig.Action(HookConfig.ActionType.PROMPT, Map.of("message", "CHILD REMINDER"), Duration.ofSeconds(5)), false, false, false);
        var provider = scripted(List.of(List.of(toolUse("r", "ReadFile", args()), complete()), List.of(delta("done"), complete())));
        try (var engine = new HookEngine(List.of(hook), new HookActions(root), () -> checker(root, PermissionMode.DEFAULT), null)) {
            new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.DEFAULT), () -> engine)
                    .run(definition(List.of(), List.of(), 20, PermissionMode.DEFAULT), "task", "label", null, root);
            assertEquals(1, calls.get());
            assertTrue(provider.receivedRequests().getLast().messages().stream().anyMatch(m -> m.content().contains("CHILD REMINDER")));
            assertTrue(engine.drainPrompts().isEmpty());
        }
    }
    @Test void childLoadSkillIsIsolatedAndCannotSpawnIndirectly() throws Exception {
        var directory = Files.createDirectories(root.resolve(".acode/skills"));
        Files.writeString(directory.resolve("inline.md"), "---\nname: inline\ndescription: local\n---\nCHILD SKILL BODY");
        Files.writeString(directory.resolve("nested.md"), "---\nname: nested\ndescription: nested\nmode: fork\n---\nNESTED");
        var parent = parent(); var registry = new ToolRegistry();
        var repository = new SkillRepository(root, home, ignored -> {}); repository.reload();
        var runtime = new SkillRuntime(repository, registry, parent);
        runtime.setForkHost((d, a, h) -> { fail("child escaped through parent's fork host"); return null; });
        registry.register(new LoadSkillTool(runtime));
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var provider = scripted(List.of(
                List.of(toolUse("inline", "LoadSkill", json.objectNode().put("name", "inline")), complete()),
                List.of(toolUse("nested", "LoadSkill", json.objectNode().put("name", "nested")), complete()),
                List.of(delta("done"), complete())));
        new SubAgentRunner(provider, parent, registry, () -> checker(root, PermissionMode.DEFAULT), null).run(null, "task", "label", null, root);
        assertTrue(runtime.activeNames().isEmpty()); assertEquals(1, parent.messageCount());
        assertTrue(provider.receivedRequests().get(1).messages().stream().anyMatch(m -> m.content().contains("CHILD SKILL BODY")));
        assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream())
                .anyMatch(b -> b instanceof ToolResultBlock r && r.isError() && r.content().contains("不能再创建")));
    }
    @Test void childOnceHooksShareSessionMarkerWithoutSharingPromptQueue() {
        var count = new AtomicInteger();
        var hook = new HookConfig("once", HookEvents.TURN_START, c -> true,
                new HookConfig.Action(HookConfig.ActionType.PROMPT, Map.of("message", "once"), Duration.ofSeconds(5)), false, true, false);
        try (var engine = new HookEngine(List.of(hook), new HookActions(root), () -> checker(root, PermissionMode.DEFAULT), id -> count.incrementAndGet())) {
            var runner = new SubAgentRunner(streaming("done"), parent(), new ToolRegistry(), () -> checker(root, PermissionMode.DEFAULT), () -> engine);
            runner.run(null, "task", "one", null, root); runner.run(null, "task", "two", null, root);
            assertEquals(1, count.get()); assertTrue(engine.drainPrompts().isEmpty());
        }
    }
    @Test void concurrentHookScopesReserveOnceBeforeExecuting() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger();
        var hook = new HookConfig("once", HookEvents.TURN_START, c -> true,
                new HookConfig.Action(HookConfig.ActionType.PROMPT, Map.of("message", "once"), Duration.ofSeconds(5)), false, true, false);
        try (var parent = new HookEngine(List.of(hook), (h, c) -> {
            count.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); return HookAction.Result.success("done");
        }, () -> checker(root, PermissionMode.DEFAULT), null);
             var first = parent.childScope(checker(root, PermissionMode.DEFAULT));
             var second = parent.childScope(checker(root, PermissionMode.DEFAULT))) {
            Thread worker = Thread.ofVirtual().start(() -> first.fire(HookEvents.TURN_START, HookContext.lifecycle(HookEvents.TURN_START, "task")));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> second.fire(HookEvents.TURN_START, HookContext.lifecycle(HookEvents.TURN_START, "task")));
                assertEquals(1, count.get());
            } finally { release.countDown(); worker.join(5000); }
        }
    }
    @Test void recentSkillContextRemovesOrphanResultsButKeepsCompletePairs() {
        var parent = parent();
        parent.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ToolUseBlock("old", "ReadFile", args()))));
        parent.addToolResults(List.of(new ToolResultBlock("old", "orphan after slicing", false)));
        parent.addMessage(ChatMessage.of(ChatMessage.Role.USER, "recent user"));
        parent.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ToolUseBlock("new", "ReadFile", args()))));
        parent.addToolResults(List.of(new ToolResultBlock("new", "keep pair", false)));
        parent.addMessage(ChatMessage.of(ChatMessage.Role.USER, "last user"));
        var provider = streaming("done");
        var runner = new SubAgentRunner(provider, parent, new ToolRegistry(), () -> checker(root, PermissionMode.DEFAULT), null);
        var skill = new SkillDefinition("recent", "recent", List.of(), null, "fork", "recent", "task", SkillSource.file("project", root.resolve("skill.md")));
        new SkillForkAdapter(() -> runner, () -> root).execute(skill, "", parent.history());
        var blocks = provider.receivedRequests().getFirst().messages().stream().flatMap(m -> m.blocks().stream()).toList();
        assertFalse(blocks.stream().anyMatch(b -> b instanceof ToolResultBlock r && r.toolUseId().equals("old")));
        assertTrue(blocks.stream().anyMatch(b -> b instanceof ToolResultBlock r && r.toolUseId().equals("new")));
        assertTrue(blocks.stream().anyMatch(b -> b instanceof ToolUseBlock u && u.id().equals("new")));
        assertEquals(7, parent.messageCount());
    }
    @Test void hookAgentExpansionAndFailedEmptyResultKeepTheirSemantics() throws Exception {
        var prompt = new AtomicReference<String>();
        var hook = new HookConfig("agent", HookEvents.POST_TOOL_USE, c -> true,
                new HookConfig.Action(HookConfig.ActionType.AGENT, Map.of("prompt", "$TOOL_NAME:$FILE_PATH:$ERROR"), Duration.ofSeconds(5)), false, false, false);
        var context = new HookContext(HookEvents.POST_TOOL_USE, "ReadFile", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("file_path", "a $b.txt"), null, "failed");
        var result = new HookActions(root, text -> { prompt.set(text); return ToolResult.failure(""); }).execute(hook, context);
        assertEquals("ReadFile:a $b.txt:failed", prompt.get()); assertFalse(result.ok()); assertEquals("", result.output());
    }
    @Test void childExternalHooksUseChildPermissionsNotParentBypass() {
        var command = new HookConfig("command", HookEvents.PRE_TOOL_USE, c -> true,
                new HookConfig.Action(HookConfig.ActionType.COMMAND, Map.of("command", "npm run format"), Duration.ofSeconds(5)), false, false, false);
        for (PermissionMode childMode : List.of(PermissionMode.DEFAULT, PermissionMode.BYPASS)) {
            var invoked = new AtomicInteger(); var parentChecker = checker(root, PermissionMode.BYPASS);
            // The action boundary is a counter: no real command is executed by this test.
            try (var hooks = new HookEngine(List.of(command), (h, c) -> { invoked.incrementAndGet(); return HookAction.Result.success("ok"); }, () -> parentChecker, null)) {
                var provider = scripted(List.of(List.of(toolUse("r", "ReadFile", args()), complete()), List.of(delta("done"), complete())));
                var registry = new ToolRegistry().register(tool("ReadFile", Permission.READ, new AtomicInteger()));
                var result = new SubAgentRunner(provider, parent(), registry, () -> parentChecker, () -> hooks)
                        .run(definition(List.of(), List.of(), 20, childMode), "task", "label", null, root);
                assertTrue(result.isSuccess()); assertEquals(childMode == PermissionMode.DEFAULT ? 0 : 1, invoked.get());
            }
        }
    }
}
