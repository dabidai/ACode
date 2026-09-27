package com.acode.subagent;

import com.acode.agent.*;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.acode.subagent.SubAgentTestSupport.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentEndToEndTest {
    @TempDir Path root;
    @TempDir Path home;
    @Test void definedAndForkDispatchReturnResultsWithoutChildEvents() {
        for (boolean fork : List.of(false, true)) assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var input = (ObjectNode) args(); if (!fork) input.put("subagent_type", "Explore");
            var scripts = new ArrayList<List<Action>>();
            scripts.add(List.of(toolUse("dispatch", "Agent", input), usage(new Usage(10, 1, 0, 0)), complete()));
            if (fork) scripts.add(List.of(toolUse("nested", "Agent", args()), complete()));
            scripts.add(List.of(delta("CHILD PRIVATE RESULT"), usage(new Usage(999, 999, 0, 0)), complete()));
            scripts.add(List.of(delta("PARENT cites CHILD PRIVATE RESULT"), usage(new Usage(20, 2, 0, 0)), complete()));
            var provider = scripted(scripts); var parent = parent(); var tools = new ToolRegistry();
            DefaultToolset.registerAll(tools);
            var definitions = new AgentRegistry(root, home, false);
            var permission = checker(root, PermissionMode.ACCEPT_EDITS);
            var runner = new SubAgentRunner(provider, parent, tools, () -> permission, null);
            tools.register(new AgentTool(() -> definitions, () -> runner));
            var agent = new Agent(provider, parent, tools, new ToolContext(root), 10);
            agent.setPermissionChecker(permission);
            var events = agent.run(); var captured = new ArrayList<AgentEvent>();
            while (agent.isRunning() || !events.isEmpty()) { var e = events.poll(100, TimeUnit.MILLISECONDS); if (e != null) captured.add(e); }
            agent.awaitTermination();
            assertEquals(Agent.Termination.NORMAL, agent.termination());
            assertEquals(fork ? 4 : 3, provider.receivedRequests().size());
            assertEquals(1, captured.stream().filter(e -> e instanceof AgentEvent.ToolUseEvent).count());
            assertEquals(List.of(10L, 20L), captured.stream().filter(AgentEvent.UsageEvent.class::isInstance)
                    .map(AgentEvent.UsageEvent.class::cast).map(e -> e.usage().inputTokens()).toList());
            assertFalse(captured.stream().anyMatch(e -> e instanceof AgentEvent.StreamText text && text.text().equals("CHILD PRIVATE RESULT")));
            assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(b -> b instanceof ToolResultBlock r && r.content().equals("CHILD PRIVATE RESULT")));
            var child = provider.receivedRequests().get(1);
            assertEquals(fork, child.messages().stream().anyMatch(m -> m.content().equals("PARENT SECRET")));
            if (fork) assertTrue(provider.receivedRequests().get(2).messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(b -> b instanceof ToolResultBlock r && r.content().equals("Fork 子 Agent 不能再创建子 Agent")));
        });
    }
    @Test void siblingDispatchesKeepHistoriesSeparateAndResultsInCallOrder() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var first = ((ObjectNode) args()).put("prompt", "FIRST TASK");
            var second = ((ObjectNode) args()).put("prompt", "SECOND TASK");
            var provider = scripted(List.of(
                    List.of(toolUse("first", "Agent", first), toolUse("second", "Agent", second), complete()),
                    List.of(delta("FIRST PRIVATE WORK"), toolUse("read", "ReadFile", args()), complete()),
                    List.of(delta("FIRST RESULT"), complete()),
                    List.of(delta("SECOND RESULT"), complete()), List.of(delta("combined"), complete())));
            var parent = parent(); var registry = new ToolRegistry();
            registry.register(tool("ReadFile", Permission.READ, new java.util.concurrent.atomic.AtomicInteger()));
            var definitions = new AgentRegistry(root, home, false);
            var permission = checker(root, PermissionMode.ACCEPT_EDITS);
            var runner = new SubAgentRunner(provider, parent, registry, () -> permission, null);
            registry.register(new AgentTool(() -> definitions, () -> runner));
            var agent = new Agent(provider, parent, registry, new ToolContext(root), 5); agent.setPermissionChecker(permission);
            var events = agent.run();
            try { while (agent.isRunning() || !events.isEmpty()) events.poll(100, TimeUnit.MILLISECONDS); }
            finally { agent.cancel(); agent.awaitTermination(); }
            assertEquals(5, provider.receivedRequests().size());
            var secondRequest = provider.receivedRequests().get(3);
            assertTrue(secondRequest.messages().getLast().content().endsWith("SECOND TASK"));
            assertFalse(secondRequest.messages().stream().anyMatch(m -> m.content().contains("FIRST")));
            assertFalse(secondRequest.messages().stream().flatMap(m -> m.blocks().stream()).anyMatch(ToolUseBlock.class::isInstance));
            var results = provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream())
                    .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
            assertEquals(List.of("first", "second"), results.stream().map(ToolResultBlock::toolUseId).toList());
            assertEquals(List.of("FIRST RESULT", "SECOND RESULT"), results.stream().map(ToolResultBlock::content).toList());
            assertFalse(parent.history().stream().anyMatch(m -> m.content().contains("FIRST PRIVATE WORK")));
        });
    }
}
