package com.acode.subagent;

import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.acode.subagent.SubAgentTestSupport.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentRunnerTest {
    @TempDir Path root;
    SubAgentRunner runner(ChatProvider provider, Conversation parent, ToolRegistry tools) {
        return new SubAgentRunner(provider, parent, tools, () -> checker(root, PermissionMode.DEFAULT), null);
    }
    @Test void definitionHasCleanContextAndExecutesTools() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, count));
        var provider = scripted(List.of(List.of(delta("working"), toolUse("r", "ReadFile", args()), complete()), List.of(delta("answer"), complete())));
        var parent = parent();
        var result = runner(provider, parent, tools).run(definition(List.of(), List.of(), 20, PermissionMode.DEFAULT), "TASK", "label", "selected", root);
        assertTrue(result.isSuccess()); assertEquals("answer", result.content()); assertEquals(1, count.get()); assertEquals(1, parent.messageCount());
        var request = provider.receivedRequests().getFirst();
        assertEquals("selected", request.model()); assertEquals(1234, request.maxTokens()); assertTrue(request.thinking());
        assertEquals(List.of("CHILD SYSTEM", "ENVIRONMENT", "TASK"), request.messages().stream().map(ChatMessage::content).toList());
        assertEquals("完成子任务「label」（test）", result.display());
    }
    @Test void forkCopiesPrefixAndForcesParentModel() {
        var parent = parent(); var provider = streaming("Scope: done");
        var result = runner(provider, parent, new ToolRegistry()).run(null, "TASK", "fork", "ignored", root);
        assertTrue(result.isSuccess()); var request = provider.receivedRequests().getFirst();
        var prefix = parent.buildRequest(List.of(), null).messages();
        assertEquals(prefix.stream().map(ChatMessage::content).toList(), request.messages().subList(0, prefix.size()).stream().map(ChatMessage::content).toList());
        assertEquals(ForkBoilerplate.TEXT + "\n\nTASK", request.messages().getLast().content());
        assertEquals("parent-model", request.model());
    }
    @Test void maliciousOutOfScopeCallsNeverExecute() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("WriteFile", Permission.WRITE, count));
        var provider = scripted(List.of(List.of(toolUse("w", "WriteFile", args()), complete()), List.of(delta("done"), complete())));
        var result = runner(provider, parent(), tools).run(definition(List.of(), List.of("WriteFile"), 20, PermissionMode.BYPASS), "task", "label", null, root);
        assertTrue(result.isSuccess()); assertEquals(0, count.get()); assertNotNull(tools.get("WriteFile"));
        assertTrue(provider.receivedRequests().get(1).messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(b -> b instanceof ToolResultBlock r && r.isError()));
    }
    @Test void parentActiveToolScopeCannotBeExpanded() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, count));
        var provider = scripted(List.of(List.of(toolUse("r", "ReadFile", args()), complete()), List.of(delta("done"), complete())));
        new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.BYPASS), null, Set::of)
                .run(null, "task", "label", null, root);
        assertTrue(provider.receivedRequests().getFirst().tools().isEmpty()); assertEquals(0, count.get());
    }
    @Test void emptyCapAndFailureSemantics() {
        var tools = new ToolRegistry(); var d = definition(List.of(), List.of(), 1, PermissionMode.DEFAULT);
        assertEquals("", runner(streaming(), parent(), tools).run(d, "task", "label", null, root).content());
        var cap = scripted(List.of(List.of(delta("partial"), toolUse("x", "none", args()), complete())));
        var partial = runner(cap, parent(), tools).run(d, "task", "label", null, root);
        assertTrue(partial.isSuccess()); assertEquals("partial", partial.content()); assertTrue(partial.display().contains("达到最大轮数 1"));
        var empty = scripted(List.of(List.of(toolUse("x", "none", args()), complete())));
        assertTrue(runner(empty, parent(), tools).run(d, "task", "label", null, root).content().contains("且未产出结果"));
        var failed = runner(failing(new InvalidRequestException("bad request")), parent(), tools).run(d, "task", "label", null, root);
        assertTrue(failed.isError()); assertTrue(failed.content().contains("执行失败"));
    }
    @Test void largeStreamIsDrainedWithoutQueueDeadlock() {
        ChatProvider provider = (request, listener) -> { for (int i=0;i<2000;i++) listener.onDelta("x"); listener.onComplete(); };
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(2000, runner(provider, parent(), new ToolRegistry()).run(null, "task", "label", null, root).content().length()));
    }
    @Test void interruptionCancelsProviderAndReturnsFailure() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var result = new AtomicReference<ToolResult>();
        ChatProvider provider = (request, listener) -> {
            entered.countDown();
            try { Thread.sleep(30000); } catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        };
        Thread thread = Thread.ofVirtual().start(() -> result.set(runner(provider, parent(), new ToolRegistry()).run(null, "task", "label", null, root)));
        assertTrue(entered.await(5, TimeUnit.SECONDS)); thread.interrupt(); thread.join(5000);
        assertFalse(thread.isAlive()); assertTrue(interrupted.await(5, TimeUnit.SECONDS)); assertTrue(result.get().isError());
    }
}
