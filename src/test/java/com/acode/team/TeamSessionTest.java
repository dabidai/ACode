package com.acode.team;

import com.acode.agent.Agent;
import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamSessionTest {
    @TempDir Path root;
    static final ObjectMapper JSON = new ObjectMapper();
    private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
    private static String text(ChatRequest request) { return request.messages().stream().map(ChatMessage::content).collect(java.util.stream.Collectors.joining("\n")); }
    private Conversation conversation() {
        var conversation = new Conversation("fake", false, 1024, 32000);
        conversation.setSystemPrompt("system"); conversation.setEnvironment(ChatMessage.of(ChatMessage.Role.USER, "environment"));
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, "parent history"));
        return conversation;
    }
    private TeamSession session(ChatProvider provider, ToolRegistry registry) {
        return new TeamSession(root, provider, conversation(), registry,
                () -> new PermissionChecker(PermissionMode.DEFAULT, root, new RuleEngine(root.resolve("user.json"), root.resolve("project.json"), root.resolve("local.json"))), () -> null,
                new TeamSession.Worktrees() {
                    public Path create(String name) throws Exception { return Files.createDirectories(root.resolve("worktrees").resolve(name.replace('/', '+'))); }
                    public void verifyRemovable(String name) throws Exception {
                        if (Files.exists(root.resolve("dirty"))) throw new java.io.IOException("未保存成果");
                    }
                    public void remove(String name) throws Exception { Files.delete(root.resolve("worktrees").resolve(name.replace('/', '+'))); }
                }, output::add);
    }
    private void finished(String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(line, "waiting for " + name);
            if (line.startsWith("队员 " + name + "：")) return;
            assertTrue(System.nanoTime() < deadline);
        }
    }
    private ToolResult call(TeamSession session, String name, String json) throws Exception {
        return session.leadTools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow()
                .execute(JSON.readTree(json), new ToolContext(root));
    }

    @Test void spawnPersistResumeAndDeleteThroughTools() throws Exception {
        var provider = FakeProvider.streaming("done");
        try (var session = session(provider, new ToolRegistry())) {
            assertTrue(call(session, "TeamCreate", "{\"team_name\":\"team\"}").isSuccess());
            assertTrue(call(session, "TaskCreate", "{\"title\":\"task\"}").isSuccess());
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isSuccess());
            finished("alice");
            Team team = session.manager().find("team").orElseThrow();
            var member = team.members().getFirst();
            assertFalse(member.isActive());
            var first = provider.receivedRequests().getFirst();
            assertTrue(text(first).contains(TeamSession.TEAM_PROMPT));
            var names = first.tools().stream().map(Tool::name).toList();
            assertTrue(names.containsAll(List.of("TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage")));
            assertFalse(names.contains("Agent")); assertFalse(names.contains("TeamCreate"));
            var transcript = new TeamTranscriptStore(team.configPath().getParent(), "alice");
            assertEquals(3, transcript.read().size());
            var mailbox = new FileMailbox(team.configPath().getParent());
            assertTrue(mailbox.unread("lead").stream().anyMatch(m -> m.message().contains("<status>completed</status>")));
            String notification = mailbox.unread("lead").stream().map(FileMailbox.Message::message).filter(m -> m.contains("<task-notification>")).findFirst().orElseThrow();
            for (String field : List.of("total_tokens", "tool_uses", "duration_ms"))
                assertTrue(java.util.regex.Pattern.compile("<" + field + ">[0-9]+</" + field + ">").matcher(notification).find());
            assertTrue(call(session, "SendMessage", "{\"to\":\"alice\",\"summary\":\"one two three four five\",\"message\":\"continue work\"}").isSuccess());
            finished("alice");
            assertEquals(member.agentID(), session.manager().member("team", "alice").orElseThrow().agentID());
            assertTrue(text(provider.receivedRequests().getLast()).contains("continue work"));
            assertTrue(text(provider.receivedRequests().getLast()).contains("done"));
            assertTrue(mailbox.unread("alice").isEmpty());
            Files.writeString(root.resolve("dirty"), "test");
            assertTrue(call(session, "TeamDelete", "{\"team_name\":\"team\"}").isError());
            assertTrue(Files.exists(team.configPath()));
            Files.delete(root.resolve("dirty"));
            assertTrue(call(session, "TeamDelete", "{\"team_name\":\"team\"}").isSuccess());
            assertFalse(Files.exists(team.configPath().getParent()));
        }
    }

    @Test void failedRequestKeepsMailAndRollsBackClaimedTasks() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        ChatProvider provider = (request, listener) -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            listener.onError(new ProviderException("failure"));
        };
        try (var session = session(provider, new ToolRegistry())) {
            Team team = session.create("team", null);
            var mailbox = new FileMailbox(team.configPath().getParent());
            mailbox.deliver("lead", "alice", "summary", "retry me", "text");
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isSuccess());
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            String actor = session.manager().member("team", "alice").orElseThrow().agentID();
            var store = new TeamTaskStore(team.configPath().getParent());
            store.create("task", null, null, null); store.update("1", actor, "in_progress", null, null);
            release.countDown(); finished("alice");
            assertEquals("pending", store.get("1").status());
            assertEquals(1, mailbox.unread("alice").size());
            assertTrue(mailbox.unread("lead").stream().anyMatch(m -> m.message().contains("<status>failed</status>")));
        } finally { release.countDown(); }
    }

    @Test void forgedToolsAndUnapprovedWritesHaveNoSideEffects() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new BaseTool("WriteProbe", "probe", Permission.WRITE) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(com.fasterxml.jackson.databind.JsonNode input, ToolContext context) { calls.incrementAndGet(); return ToolResult.success("written"); }
        });
        var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("1", "WriteProbe", JSON.createObjectNode()),
                FakeProvider.toolUse("2", "Agent", JSON.createObjectNode()), FakeProvider.complete()), List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        try (var session = session(provider, registry)) {
            session.create("team", null);
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isSuccess());
            finished("alice"); assertEquals(0, calls.get());
            assertTrue(JSON.valueToTree(provider.receivedRequests().getLast().messages()).toString().contains("WriteProbe"));
        }
    }

    @Test void coordinatorFiltersRequestAndForgedExecution() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new BaseTool("WriteProbe", "probe", Permission.WRITE) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(com.fasterxml.jackson.databind.JsonNode input, ToolContext context) { calls.incrementAndGet(); return ToolResult.success("written"); }
        });
        var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("1", "WriteProbe", JSON.createObjectNode()), FakeProvider.complete()), List.of(FakeProvider.complete())));
        try (var session = session(provider, registry)) {
            var agent = new Agent(provider, conversation(), registry, new ToolContext(root), 4);
            session.configureLead(agent, true);
            var events = agent.run();
            while (agent.isRunning() || !events.isEmpty()) events.poll(5, TimeUnit.SECONDS);
            agent.awaitTermination(); assertEquals(0, calls.get());
            assertTrue(provider.receivedRequests().getFirst().tools().isEmpty());
            assertTrue(text(provider.receivedRequests().getFirst()).contains("理解不能外包"));
            assertTrue(provider.receivedRequests().getFirst().messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.SYSTEM && m.content().contains("Verification")));
        }
        assertFalse(CoordinatorMode.enabled(false, "true"));
        assertFalse(CoordinatorMode.enabled(true, null));
        assertTrue(CoordinatorMode.enabled(true, "yes"));
    }

    @Test void planApprovalOnlyAllowsExactApprovedOperation() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new BaseTool("WriteProbe", "probe", Permission.WRITE) {
            protected List<ParamSpec> paramSpecs() { return List.of(ParamSpec.required("value", ParamSpec.Type.STRING, "value")); }
            protected ToolResult doExecute(com.fasterxml.jackson.databind.JsonNode input, ToolContext context) {
                assertEquals("approved", input.path("value").asText()); calls.incrementAndGet(); return ToolResult.success("written");
            }
        });
        var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("plan", "SendMessage", JSON.createObjectNode()
                        .put("to", "lead").put("summary", "please review this proposed plan").put("message", "Please approve my plan")), FakeProvider.complete()),
                List.of(FakeProvider.delta("waiting"), FakeProvider.complete()),
                List.of(FakeProvider.delta("revised plan"), FakeProvider.complete()),
                List.of(FakeProvider.toolUse("1", "WriteProbe", JSON.createObjectNode().put("value", "approved")),
                        FakeProvider.toolUse("2", "WriteProbe", JSON.createObjectNode().put("value", "not approved")), FakeProvider.complete()),
                List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        try (var session = session(provider, registry)) {
            session.create("team", null);
            assertTrue(session.spawn("team", "alice", "work", null, null, true).isSuccess()); finished("alice");
            assertFalse(provider.receivedRequests().getFirst().tools().stream().anyMatch(t -> t.name().equals("WriteProbe")));
            assertTrue(new FileMailbox(session.manager().find("team").orElseThrow().configPath().getParent()).unread("lead").stream()
                    .anyMatch(m -> m.message().equals("Please approve my plan")));
            var rejection = JSON.createObjectNode().put("to", "alice").put("messageType", "plan_approval_response")
                    .put("message", "{\"approved\":false,\"feedback\":\"add verification\"}");
            assertTrue(call(session, "SendMessage", rejection.toString()).isSuccess()); finished("alice");
            assertTrue(text(provider.receivedRequests().getLast()).contains("add verification"));
            assertEquals(0, calls.get());
            var message = JSON.createObjectNode().put("approved", true);
            message.putArray("operations").addObject().put("tool", "WriteProbe").putObject("input").put("value", "approved");
            var input = JSON.createObjectNode().put("to", "alice").put("messageType", "plan_approval_response").put("message", message.toString());
            assertTrue(call(session, "SendMessage", input.toString()).isSuccess()); finished("alice");
            assertEquals(1, calls.get());
        }
    }

    @Test void cancellationStopsRuntimeAndReturnsItsTask() throws Exception {
        var entered = new CountDownLatch(1);
        ChatProvider provider = (request, listener) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try (var session = session(provider, new ToolRegistry())) {
            Team team = session.create("team", null);
            new FileMailbox(team.configPath().getParent()).deliver("lead", "alice", "summary", "cancel retry", "text");
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isSuccess());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var store = new TeamTaskStore(team.configPath().getParent());
            store.create("task", null, null, null);
            store.update("1", session.manager().member("team", "alice").orElseThrow().agentID(), "in_progress", null, null);
            assertTrue(call(session, "TeamDelete", "{\"team_name\":\"team\"}").isError());
            session.close();
            assertFalse(session.manager().member("team", "alice").orElseThrow().isActive());
            assertEquals("pending", store.get("1").status());
            assertEquals(1, new FileMailbox(team.configPath().getParent()).unread("alice").size());
            assertTrue(new FileMailbox(team.configPath().getParent()).unread("lead").stream().anyMatch(m -> m.message().contains("<status>killed</status>")));
        }
    }

    @Test void twoTeammatesCompleteDependencyGraphAndExchangeMail() throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<ChatRequest>();
        var counters = new ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>();
        var start = new CyclicBarrier(2);
        ChatProvider provider = (request, listener) -> {
            requests.add(request);
            String who = text(request).contains("ROLE_ALICE") ? "alice" : "bob";
            int step = counters.computeIfAbsent(who, ignored -> new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement();
            int first = who.equals("alice") ? 1 : 2;
            if (step == 0) try { start.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
            if (step < 4) listener.onToolUse(new ToolUseBlock(who + step, "TaskUpdate", JSON.createObjectNode()
                    .put("taskID", Integer.toString(step < 2 ? first : first + 2)).put("status", step % 2 == 0 ? "in_progress" : "completed")));
            if (step == 0 && who.equals("alice")) listener.onToolUse(new ToolUseBlock("mail", "SendMessage", JSON.createObjectNode()
                    .put("to", "bob").put("summary", "one two three four five").put("message", "hello teammate")));
            if (step >= 4) listener.onDelta("done");
            listener.onComplete();
        };
        try (var session = session(provider, new ToolRegistry())) {
            Team team = session.create("team", null);
            for (String input : List.of("{\"title\":\"first\"}", "{\"title\":\"second\"}",
                    "{\"title\":\"third\",\"addBlockedBy\":[\"1\"]}", "{\"title\":\"fourth\",\"addBlockedBy\":[\"2\"]}"))
                assertTrue(call(session, "TaskCreate", input).isSuccess());
            assertTrue(call(session, "TaskUpdate", "{\"taskID\":\"3\",\"status\":\"in_progress\"}").isError());
            assertTrue(session.spawn("team", "alice", "ROLE_ALICE", null, null, false).isSuccess());
            assertTrue(session.spawn("team", "bob", "ROLE_BOB", null, null, false).isSuccess());
            var completed = new HashSet<String>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (completed.size() < 2) {
                String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(line);
                for (String name : List.of("alice", "bob")) if (line.equals("队员 " + name + "：completed")) completed.add(name);
                assertTrue(System.nanoTime() < deadline);
            }
            var tasks = new TeamTaskStore(team.configPath().getParent()).list();
            assertEquals(4, tasks.size()); assertTrue(tasks.stream().allMatch(t -> t.status().equals("completed")));
            assertTrue(requests.stream().anyMatch(r -> text(r).contains("ROLE_BOB") && text(r).contains("hello teammate")));
            assertTrue(new FileMailbox(team.configPath().getParent()).unread("lead").stream()
                    .filter(m -> m.message().contains("<task-notification>")).count() >= 2);
        }
    }

    @Test void definedMemberStartsFreshInItsAllocatedDirectory() throws Exception {
        var actualRoot = new java.util.concurrent.atomic.AtomicReference<Path>();
        var registry = new ToolRegistry();
        registry.register(new BaseTool("ReadProbe", "probe", Permission.READ) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(com.fasterxml.jackson.databind.JsonNode input, ToolContext context) {
                actualRoot.set(context.workingDirectory()); return ToolResult.success("root");
            }
        });
        var provider = FakeProvider.scripted(List.of(List.of(FakeProvider.toolUse("read", "ReadProbe", JSON.createObjectNode()), FakeProvider.complete()),
                List.of(FakeProvider.delta("done"), FakeProvider.complete())));
        try (var session = session(provider, registry)) {
            session.create("team", null);
            var definition = new com.acode.subagent.AgentDefinition("Test", "test", List.of("ReadProbe"), List.of(), "inherit", 5,
                    PermissionMode.DEFAULT, "defined system", null, "test");
            assertTrue(session.spawn("team", "alice", "defined prompt", definition, null, false).isSuccess()); finished("alice");
            assertFalse(text(provider.receivedRequests().getFirst()).contains("parent history"));
            assertTrue(text(provider.receivedRequests().getFirst()).contains("defined prompt"));
            assertEquals(session.manager().member("team", "alice").orElseThrow().worktreePath(), actualRoot.get());
            assertNotEquals(root, actualRoot.get());
        }
    }
}
