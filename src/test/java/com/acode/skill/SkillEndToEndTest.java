package com.acode.skill;

import com.acode.agent.*;
import com.acode.conversation.Conversation;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.acode.skill.SkillRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillEndToEndTest {
    @TempDir Path root;
    static final ObjectMapper JSON = new ObjectMapper();
    static FakeProvider.Action load(String id, String name) {
        return FakeProvider.toolUse(id, "LoadSkill", JSON.createObjectNode().put("name", name));
    }
    record Fixture(Conversation conversation, ToolRegistry tools, SkillRuntime runtime) {}
    Fixture fixture() {
        var repo = new SkillRepository(root, null, s -> {}); repo.reload();
        var c = new Conversation("default", false, 4000, 200000);
        c.addMessage(ChatMessage.of(ChatMessage.Role.USER, "test this project"));
        var tools = new ToolRegistry(); DefaultToolset.registerAll(tools);
        var runtime = new SkillRuntime(repo, tools, c); tools.register(new LoadSkillTool(runtime));
        return new Fixture(c, tools, runtime);
    }
    Agent agent(Fixture f, FakeProvider provider, boolean plan) {
        var agent = new Agent(provider, f.conversation, f.tools, new ToolContext(root), 10);
        agent.setSkillRuntime(f.runtime); agent.setPlanMode(plan); return agent;
    }
    static void finish(Agent agent) {
        agent.run(); assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), agent::awaitTermination);
        assertNotEquals(Agent.Termination.ERROR, agent.termination());
    }
    @Test void modelLoadingHasPairedBatchThenOrderedBodiesAndNextRequestBoundary() throws Exception {
        write(root, "a.md", definition("a", "allowedTools: [ReadFile, Grep]\nmodel: selected", "BODY-A"));
        write(root, "b.md", definition("b", "allowedTools: [Grep, Glob]", "BODY-B"));
        Path data = root.resolve("data.txt"); Files.writeString(data, "data");
        var f = fixture(); var original = f.tools.availableList();
        var provider = FakeProvider.scripted(List.of(
                List.of(load("a", "a"), FakeProvider.toolUse("read", "ReadFile", JSON.createObjectNode().put("file_path", data.toString())),
                        load("b", "b"), FakeProvider.complete()),
                List.of(FakeProvider.toolUse("write", "WriteFile", JSON.createObjectNode().put("file_path", root.resolve("forbidden").toString()).put("content", "bad")), FakeProvider.complete()),
                List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        finish(agent(f, provider, false));
        var history = f.conversation.history();
        assertEquals(3, history.get(1).blocks().size());
        assertEquals(List.of("a", "read", "b"), history.get(2).blocks().stream().map(b -> ((ToolResultBlock)b).toolUseId()).toList());
        assertTrue(history.get(3).content().contains("BODY-A")); assertTrue(history.get(4).content().contains("BODY-B"));
        assertEquals(history, Conversation.sanitize(history));
        var next = provider.receivedRequests().get(1);
        assertEquals("selected", next.model());
        assertEquals(Set.of("Grep", "LoadSkill"), new HashSet<>(next.tools().stream().map(Tool::name).toList()));
        assertFalse(Files.exists(root.resolve("forbidden"))); assertTrue(((ToolResultBlock)history.get(6).blocks().getFirst()).isError());
        assertEquals(original, f.tools.availableList()); assertEquals("default", f.conversation.model());
    }
    @Test void planDeniesBashEvenWhenSkillAllowsIt() throws Exception {
        write(root, "a.md", definition("a", "allowedTools: [Bash, ReadFile]", "BODY"));
        var f = fixture(); var count = new AtomicInteger();
        var provider = FakeProvider.scripted(List.of(List.of(load("load", "a"), FakeProvider.complete()),
                List.of(FakeProvider.toolUse("bash", "Bash", JSON.createObjectNode().put("command", "echo forbidden")), FakeProvider.complete()),
                List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        var agent = agent(f, provider, true); agent.setConfirmationGate((call, events, cancelled) -> {
            count.incrementAndGet(); return com.acode.permission.PermissionResponse.ALLOW;
        });
        finish(agent);
        assertFalse(provider.receivedRequests().get(1).tools().stream().anyMatch(t -> t.name().equals("Bash")));
        assertEquals(0, count.get());
        assertTrue(f.conversation.history().stream().flatMap(m -> m.blocks().stream())
                .anyMatch(b -> b instanceof ToolResultBlock r && r.toolUseId().equals("bash") && r.isError()));
    }
    @Test void failuresAndDuplicateDoNotInjectBodies() throws Exception {
        write(root, "fork.md", definition("fork", "mode: fork", "FORK BODY"));
        write(root, "a.md", definition("a", "", "BODY-A"));
        var f = fixture();
        var provider = FakeProvider.scripted(List.of(
                List.of(load("missing", "missing"), load("fork", "fork"), load("first", "a"), load("second", "a"), FakeProvider.complete()),
                List.of(load("third", "a"), FakeProvider.complete()), List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        finish(agent(f, provider, false));
        assertEquals(1, f.conversation.history().stream().filter(m -> m.content().contains("BODY-A")).count());
        assertEquals(List.of("a"), f.runtime.activeNames());
        assertFalse(f.conversation.history().stream().anyMatch(m -> m.content().contains("FORK BODY")));
        assertTrue(f.conversation.history().stream().flatMap(m -> m.blocks().stream()).anyMatch(b ->
                b instanceof ToolResultBlock r && r.content().contains("already in context")));
    }
    @Test void cancellingOrRebuildingDuringBatchDiscardsStagedLoad() throws Exception {
        for (boolean cancel : List.of(true, false)) {
            write(root, "a.md", definition("a", "model: other", "BODY-A"));
            var f = fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            f.tools.register(new Tool() {
                public String name() { return "Wait"; }
                public String description() { return "wait"; }
                public Permission permission() { return Permission.READ; }
                public com.fasterxml.jackson.databind.JsonNode inputSchema() { return JSON.createObjectNode(); }
                public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input, ToolContext ctx) {
                    entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return ToolResult.success("waited");
                }
            });
            var provider = FakeProvider.scripted(List.of(List.of(load("a", "a"),
                    FakeProvider.toolUse("wait", "Wait", JSON.createObjectNode()), FakeProvider.complete()),
                    List.of(FakeProvider.delta("done"), FakeProvider.complete())));
            var agent = agent(f, provider, false); agent.run();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            if (cancel) agent.cancel(); else f.conversation.replaceAll(List.of(ChatMessage.of(ChatMessage.Role.USER, "summary")));
            release.countDown(); assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), agent::awaitTermination);
            assertTrue(f.runtime.activeNames().isEmpty()); assertNull(f.runtime.snapshot().model());
            assertFalse(f.conversation.history().stream().anyMatch(m -> m.content().contains("BODY-A")));
        }
    }

    @Test void skillWhitelistStillPassesThroughPermissionChecker() throws Exception {
        write(root, "a.md", definition("a", "allowedTools: [WriteFile]", "BODY"));
        var f = fixture();
        f.runtime.commit(f.runtime.prepare("a", null));
        var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("write", "WriteFile",
                        JSON.createObjectNode().put("file_path", root.resolve("denied").toString()).put("content", "bad")), FakeProvider.complete()),
                List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        var agent = agent(f, provider, false);
        Files.writeString(root.resolve("project.yaml"), "rules:\n  - rule: WriteFile(*)\n    effect: deny\n");
        agent.setPermissionChecker(new com.acode.permission.PermissionChecker(com.acode.permission.PermissionMode.PLAN,
                root, new com.acode.permission.RuleEngine(root.resolve("global.yaml"), root.resolve("project.yaml"), root.resolve("local.yaml"))));
        finish(agent);
        assertFalse(Files.exists(root.resolve("denied")));
        assertTrue(f.conversation.history().stream().flatMap(m -> m.blocks().stream()).anyMatch(b ->
                b instanceof ToolResultBlock r && r.isError() && r.content().contains("权限拒绝")));
    }
}
