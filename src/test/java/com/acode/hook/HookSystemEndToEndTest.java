package com.acode.hook;

import com.acode.agent.*;
import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.session.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HookSystemEndToEndTest {
    @TempDir Path root;
    static final ObjectMapper JSON = new ObjectMapper();
    static ToolRegistry tools() { var tools = new ToolRegistry(); DefaultToolset.registerAll(tools); return tools; }
    PermissionChecker checker() { return new PermissionChecker(PermissionMode.BYPASS, root, new RuleEngine(root.resolve("u"), root.resolve("p"), root.resolve("l"))); }
    Agent run(FakeProvider provider, Conversation conversation, HookEngine engine) {
        var agent = new Agent(provider, conversation, tools(), new ToolContext(root), 5);
        agent.setHookEngine(engine, "original"); agent.setPermissionChecker(checker()); agent.run();
        assertTimeoutPreemptively(Duration.ofSeconds(10), agent::awaitTermination); assertEquals(Agent.Termination.NORMAL, agent.termination()); return agent;
    }
    Conversation conversation() { var c = new Conversation("test", false, 4000, 200000); c.addMessage(ChatMessage.of(ChatMessage.Role.USER, "original")); return c; }
    @Test void rejectedToolIsNotWrittenAndReasonReachesModel() {
        Path target = root.resolve("denied.txt"); var calls = new AtomicInteger();
        var h = HookEngineTest.hook("refuse", HookEvents.PRE_TOOL_USE, true, false, false);
        try (var engine = new HookEngine(List.of(h, HookEngineTest.hook("never", HookEvents.PRE_TOOL_USE, false, false, false)), (hook, c) -> {
            calls.incrementAndGet(); return HookAction.Result.success("blocked by policy");
        }, null, null)) {
            var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("write", "WriteFile", JSON.createObjectNode().put("file_path", target.toString()).put("content", "bad")), FakeProvider.complete()), List.of(FakeProvider.delta("alternative"), FakeProvider.complete())));
            var c = conversation(); run(provider, c, engine);
            assertFalse(Files.exists(target)); assertEquals(1, calls.get());
            assertTrue(provider.receivedRequests().get(1).messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(b -> b instanceof ToolResultBlock r && r.isError() && r.content().equals("Hook 拒绝：blocked by policy")));
        }
    }
    @Test void postCommandActuallyRunsAndPromptEntersNextRequestOnly() throws Exception {
        Path target = root.resolve("written.txt");
        HookLoaderTest.write(root.resolve(".acode/hooks.yaml"), """
                hooks:
                  - event: post_tool_use
                    action: {type: command, command: 'echo marker > marker.txt'}
                  - event: post_tool_use
                    action: {type: prompt, message: 'POST-$TOOL_NAME'}
                  - event: turn_start
                    action: {type: prompt, message: 'START-$MESSAGE'}
                  - event: turn_end
                    action: {type: prompt, message: 'END-$MESSAGE'}
                """);
        var loaded = HookLoader.load(root, root.resolve("home")); assertTrue(loaded.errors().isEmpty());
        try (var engine = new HookEngine(loaded.hooks(), new HookActions(root), this::checker, null)) {
            var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("write", "WriteFile", JSON.createObjectNode().put("file_path", target.toString()).put("content", "good")), FakeProvider.complete()), List.of(FakeProvider.delta("done"), FakeProvider.complete())));
            var c = conversation(); run(provider, c, engine);
            assertEquals("good", Files.readString(target)); assertTrue(Files.readString(root.resolve("marker.txt")).contains("marker"));
            assertTrue(provider.receivedRequests().get(0).messages().getLast().content().contains("START-original"));
            assertTrue(provider.receivedRequests().get(1).messages().getLast().content().contains("POST-WriteFile"));
            assertFalse(provider.receivedRequests().get(1).messages().stream().anyMatch(m -> m.content().contains("START-original")));
            assertFalse(c.history().stream().anyMatch(m -> m.content().contains("POST-") || m.content().contains("START-")));
            assertEquals(List.of("END-original"), engine.drainPrompts());
        }
    }
    @Test void onceSurvivesRewriteAndResumeAndNewSessionResets() throws Exception {
        var store = new SessionStore(root); var recorder = new SessionRecorder(store);
        var hook = HookEngineTest.hook("once", HookEvents.SESSION_START, false, true, false);
        try (var engine = new HookEngine(List.of(hook), new HookActions(root), null, recorder::addHookOnce)) {
            engine.fire(HookEvents.SESSION_START, HookContext.lifecycle(HookEvents.SESSION_START, null));
            assertEquals(List.of("once"), engine.drainPrompts());
            recorder.append(ChatMessage.of(ChatMessage.Role.USER, "hello"));
            Path file = recorder.file();
            recorder.rewrite(List.of(ChatMessage.of(ChatMessage.Role.USER, "summary")));
            assertTrue(Files.readAllLines(file).getFirst().contains("hook_once"));
            assertEquals(1, SessionStore.readEntries(file).size());
            assertEquals(Set.of("once"), SessionStore.readHookOnceIds(file));
            assertTrue(SessionCodec.decode(Files.readAllLines(file).getFirst()).isEmpty());
            recorder.bind(file); engine.loadOnceIds(SessionStore.readHookOnceIds(file));
            engine.fire(HookEvents.SESSION_START, HookContext.lifecycle(HookEvents.SESSION_START, null)); assertTrue(engine.drainPrompts().isEmpty());
            recorder.rewrite(List.of()); assertEquals(Set.of("once"), SessionStore.readHookOnceIds(file));
            recorder.bind(null); engine.loadOnceIds(Set.of());
            engine.fire(HookEvents.SESSION_START, HookContext.lifecycle(HookEvents.SESSION_START, null)); assertEquals(List.of("once"), engine.drainPrompts());
            assertNotEquals(file, recorder.file());
        } finally { recorder.close(); }
    }
    @Test void permissionDenialAndCancelledCallsNeverFireHooks() {
        var counter = new AtomicInteger();
        var hook = HookEngineTest.hook("pre", HookEvents.PRE_TOOL_USE, false, false, false);
        try (var engine = new HookEngine(List.of(hook), (h, c) -> { counter.incrementAndGet(); return HookAction.Result.success(""); }, null, null)) {
            var executor = new StreamingToolExecutor(tools(), new ToolContext(root), checker(), ConfirmationGate.ALWAYS_ALLOW);
            executor.setHookEngine(engine);
            var denied = new ToolUseBlock("deny", "Bash", JSON.createObjectNode().put("command", "rm -rf /"));
            var events = new java.util.concurrent.LinkedBlockingQueue<AgentEvent>();
            var results = executor.execute(List.of(denied), events, new java.util.concurrent.atomic.AtomicBoolean());
            assertTrue(results.getFirst().isError()); assertEquals(0, counter.get());
            executor.execute(List.of(new ToolUseBlock("cancel", "ReadFile", JSON.createObjectNode().put("file_path", "x"))), events, new java.util.concurrent.atomic.AtomicBoolean(true));
            assertEquals(0, counter.get());
        }
    }
    @Test void toolFailureFiresPostWithErrorAndNoConfigLeavesRequestUnchanged() {
        var errors = new ArrayList<String>();
        var hook = HookEngineTest.hook("post", HookEvents.POST_TOOL_USE, false, false, false);
        try (var engine = new HookEngine(List.of(hook), (h, c) -> { errors.add(c.error()); return HookAction.Result.success(""); }, null, null)) {
            var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("read", "ReadFile", JSON.createObjectNode().put("file_path", root.resolve("missing").toString())), FakeProvider.complete()), List.of(FakeProvider.complete())));
            run(provider, conversation(), engine); assertEquals(1, errors.size()); assertFalse(errors.getFirst().isBlank());
        }
        var c = conversation();
        assertEquals(c.buildRequest(List.of(), null).messages(), c.buildRequestWithReminders(List.of(), List.of(), null).messages());
    }

    @Test void interactiveToolUsesPreAndPostAfterConfirmationAndRejectSkipsPost() {
        var trace = new ArrayList<String>();
        class Interactive implements Tool, InteractiveTool {
            public String name() { return "Interactive"; }
            public String description() { return "test"; }
            public Permission permission() { return Permission.WRITE; }
            public com.fasterxml.jackson.databind.JsonNode inputSchema() { return JSON.createObjectNode(); }
            public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input, ToolContext context) { throw new AssertionError(); }
            public ToolResult executeInteractive(ToolUseBlock call, java.util.concurrent.BlockingQueue<AgentEvent> events, java.util.concurrent.atomic.AtomicBoolean cancelled) {
                trace.add("tool"); return ToolResult.success("done");
            }
        }
        var registry = new ToolRegistry(); registry.register(new Interactive());
        var checker = checker(); checker.setMode(PermissionMode.DEFAULT);
        var executor = new StreamingToolExecutor(registry, new ToolContext(root), checker, (call, events, cancelled) -> { trace.add("confirm"); return PermissionResponse.ALLOW; });
        var hooks = List.of(HookEngineTest.hook("pre", HookEvents.PRE_TOOL_USE, false, false, false), HookEngineTest.hook("post", HookEvents.POST_TOOL_USE, false, false, false));
        var calls = List.of(new ToolUseBlock("interactive", "Interactive", JSON.createObjectNode()));
        try (var engine = new HookEngine(hooks, (h, c) -> { trace.add(h.id()); return HookAction.Result.success(""); }, null, null)) {
            executor.setHookEngine(engine);
            executor.execute(calls, new java.util.concurrent.LinkedBlockingQueue<>(), new java.util.concurrent.atomic.AtomicBoolean());
            assertEquals(List.of("confirm", "pre", "tool", "post"), trace);
        }
        trace.clear();
        try (var engine = new HookEngine(List.of(HookEngineTest.hook("reject", HookEvents.PRE_TOOL_USE, true, false, false), hooks.getLast()), (h, c) -> { trace.add(h.id()); return HookAction.Result.success(""); }, null, null)) {
            executor.setHookEngine(engine);
            assertEquals("Hook 拒绝", executor.execute(calls, new java.util.concurrent.LinkedBlockingQueue<>(), new java.util.concurrent.atomic.AtomicBoolean()).getFirst().content());
            assertEquals(List.of("confirm", "reject"), trace);
        }
        trace.clear();
        try (var engine = new HookEngine(hooks, (h, c) -> { fail("denied by user"); return null; }, null, null)) {
            executor = new StreamingToolExecutor(registry, new ToolContext(root), checker, (call, events, cancelled) -> PermissionResponse.DENY);
            executor.setHookEngine(engine);
            assertTrue(executor.execute(calls, new java.util.concurrent.LinkedBlockingQueue<>(), new java.util.concurrent.atomic.AtomicBoolean()).getFirst().isError());
        }
    }

    @Test void cancellingExchangeDoesNotFireTurnEnd() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var counts = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        try (var engine = new HookEngine(List.of(HookEngineTest.hook("start", HookEvents.TURN_START, false, false, false), HookEngineTest.hook("end", HookEvents.TURN_END, false, false, false)),
                (h, c) -> { counts.computeIfAbsent(h.id(), key -> new AtomicInteger()).incrementAndGet(); return HookAction.Result.success(""); }, null, null)) {
            ChatProvider provider = (request, listener) -> {
                started.countDown();
                try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                listener.onComplete();
            };
            var agent = new Agent(provider, conversation(), tools(), new ToolContext(root), 5);
            agent.setHookEngine(engine, "original"); agent.run();
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS)); agent.cancel(); finish.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), agent::awaitTermination);
            assertEquals(1, counts.get("start").get()); assertFalse(counts.containsKey("end"));
        } finally { finish.countDown(); }
    }
}
