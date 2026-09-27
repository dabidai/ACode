package com.acode.subagent;

import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** Adversarial histories, recovery and cancellation across the actual runner boundary. */
class SubAgentBoundaryTest {
    @TempDir Path root;
    private SubAgentRunner runner(ChatProvider provider, com.acode.conversation.Conversation parent, ToolRegistry tools) {
        return new SubAgentRunner(provider, parent, tools, () -> checker(root, PermissionMode.DEFAULT), null);
    }

    @Test void forkCopiesStructuredHistoryAndMutableArgumentsDeeply() throws Exception {
        var parent = parent(); var json = new ObjectMapper();
        ObjectNode originalArgs = json.createObjectNode().put("file_path", "original.txt");
        originalArgs.putObject("nested").put("value", "original");
        parent.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new TextBlock("read"), new ToolUseBlock("read", "ReadFile", originalArgs))));
        parent.addToolResults(List.of(new ToolResultBlock("read", "old contents", false)));
        String before = json.writeValueAsString(parent.buildRequest(List.of(), null).messages());
        ChatProvider provider = (request, listener) -> {
            assertEquals(before, assertDoesNotThrow(() -> json.writeValueAsString(request.messages().subList(0, request.messages().size() - 1))));
            var copied = request.messages().stream().flatMap(m -> m.blocks().stream()).filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast).findFirst().orElseThrow();
            ((ObjectNode) copied.input().path("nested")).put("value", "changed by child");
            listener.onDelta("done"); listener.onComplete();
        };
        assertTrue(runner(provider, parent, new ToolRegistry()).run(null, "task", "label", null, root).isSuccess());
        assertEquals("original", originalArgs.path("nested").path("value").asText());
        assertEquals(before, json.writeValueAsString(parent.buildRequest(List.of(), null).messages()));
    }

    @Test void retryDiscardsFailedAttemptTextAndTools() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, count));
        var provider = scripted(List.of(
                List.of(delta("FAILED PREFIX"), toolUse("bad", "ReadFile", args()), error(new RateLimitException("retry"))),
                List.of(delta("clean result"), complete())));
        var result = runner(provider, parent(), tools).run(null, "task", "label", null, root);
        assertTrue(result.isSuccess()); assertEquals("clean result", result.content()); assertEquals(0, count.get());
        assertEquals(2, provider.receivedRequests().size());
        assertFalse(provider.receivedRequests().getLast().messages().stream().anyMatch(m -> m.content().contains("FAILED PREFIX")));
        assertFalse(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(ToolUseBlock.class::isInstance));
    }

    @Test void finalEmptyReplyDoesNotReturnEarlierProgressAsAnswer() {
        var provider = scripted(List.of(List.of(delta("working"), toolUse("r", "ReadFile", args()), complete()), List.of(complete())));
        var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, new AtomicInteger()));
        var result = runner(provider, parent(), tools).run(null, "task", "label", null, root);
        assertTrue(result.isSuccess()); assertEquals("", result.content()); assertNotNull(result.display());
    }

    @Test void capDoesNotExecuteLastRoundToolsAndRetainsEarlierText() {
        var calls = new AtomicInteger(); var tools = new ToolRegistry().register(tool("ReadFile", Permission.READ, calls));
        var provider = scripted(List.of(
                List.of(delta("partial findings"), toolUse("first", "ReadFile", args()), complete()),
                List.of(toolUse("last", "ReadFile", args()), complete())));
        var result = runner(provider, parent(), tools).run(definition(List.of(), List.of(), 2, PermissionMode.DEFAULT), "task", "label", null, root);
        assertTrue(result.isSuccess()); assertEquals("partial findings", result.content());
        assertEquals(1, calls.get()); assertTrue(result.display().contains("达到最大轮数 2"));
    }

    @Test void toolExceptionBecomesFeedbackAndChildCanRecover() {
        var tools = new ToolRegistry().register(new BaseTool("Broken", "fails", Permission.READ) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(JsonNode input, ToolContext context) { throw new IllegalStateException("broken tool"); }
        });
        var provider = scripted(List.of(List.of(toolUse("b", "Broken", args()), complete()), List.of(delta("recovered"), complete())));
        var result = runner(provider, parent(), tools).run(null, "task", "label", null, root);
        assertEquals("recovered", result.content());
        assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream())
                .anyMatch(b -> b instanceof ToolResultBlock r && r.isError() && r.content().contains("broken tool")));
    }

    @Test void missingPermissionCheckerFailsBeforeAnyModelRequest() {
        var provider = streaming("should not run");
        var result = new SubAgentRunner(provider, parent(), new ToolRegistry(), () -> null, null).run(null, "task", "label", null, root);
        assertTrue(result.isError()); assertTrue(result.content().contains("缺少权限检查器")); assertTrue(provider.receivedRequests().isEmpty());
    }

    @Test void cancellationWaitsForToolCleanupAndPreventsFollowingCalls() throws Exception {
        var started = new CountDownLatch(1); var cleaning = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1); var result = new AtomicReference<ToolResult>(); var subsequent = new AtomicInteger();
        var tools = new ToolRegistry().register(new BaseTool("SlowWrite", "controlled slow tool", Permission.WRITE) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) {
                    cleaning.countDown();
                    // Simulate a resource that needs cleanup after accepting interruption.
                    boolean done = false;
                    while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                }
                return ToolResult.success("closed");
            }
        }).register(tool("NextWrite", Permission.WRITE, subsequent));
        var provider = scripted(List.of(List.of(toolUse("slow", "SlowWrite", args()), toolUse("next", "NextWrite", args()), complete()), List.of(delta("unexpected"), complete())));
        var runner = runner(provider, parent(), tools);
        Thread caller = Thread.ofVirtual().start(() -> {
            try { result.set(runner.run(null, "task", "label", null, root, null, call -> true)); }
            finally { finished.countDown(); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS)); caller.interrupt(); assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertFalse(finished.await(150, TimeUnit.MILLISECONDS), "runner must wait for real cleanup");
        } finally { release.countDown(); caller.join(5000); }
        assertFalse(caller.isAlive()); assertNotNull(result.get()); assertTrue(result.get().isError());
        assertEquals(0, subsequent.get()); assertEquals(1, provider.receivedRequests().size());
    }
}
