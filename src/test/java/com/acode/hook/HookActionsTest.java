package com.acode.hook;

import com.acode.permission.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HookActionsTest {
    @TempDir Path root;
    static HookConfig config(HookConfig.ActionType type, Map<String, String> values, Duration timeout) {
        return new HookConfig("action", HookEvents.PRE_TOOL_USE, c -> true, new HookConfig.Action(type, values, timeout), true, false, false);
    }
    PermissionChecker checker(PermissionMode mode) {
        return new PermissionChecker(mode, root, new RuleEngine(root.resolve("user"), root.resolve("project"), root.resolve("local")));
    }
    @Test void commandOutputExitTimeoutAndTruncation() throws Exception {
        boolean bash = new com.acode.tool.impl.ShellDetector().shellName().equals("git-bash");
        var actions = new HookActions(root); var c = HookContext.lifecycle(HookEvents.TURN_START, "hello");
        var result = actions.execute(config(HookConfig.ActionType.COMMAND, Map.of("command", "echo $MESSAGE"), Duration.ofSeconds(3)), c);
        assertTrue(result.ok()); assertTrue(result.output().contains("hello"));
        result = actions.execute(config(HookConfig.ActionType.COMMAND, Map.of("command", bash ? "echo failure; exit 3" : "echo failure & exit /b 3"), Duration.ofSeconds(3)), c);
        assertFalse(result.ok()); assertTrue(result.output().contains("failure"));
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertFalse(actions.execute(config(HookConfig.ActionType.COMMAND, Map.of("command", bash ? "sleep 10" : "ping -n 10 127.0.0.1 > nul"), Duration.ofMillis(100)), c).ok()));
        result = actions.execute(config(HookConfig.ActionType.COMMAND, Map.of("command", bash ? "printf '%040000d' 0" : "for /L %i in (1,1,4000) do @echo 1234567890"), Duration.ofSeconds(4)), c);
        assertTrue(result.ok()); assertTrue(result.output().endsWith("…（输出过长，已截断）")); assertTrue(result.output().length() < 30100);
    }
    @Test void httpDefaultPostBodyLimitErrorsAndNoRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new ArrayList<String>(); var redirected = new AtomicInteger();
        server.createContext("/ok", exchange -> {
            seen.add(exchange.getRequestMethod() + ":" + new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] text = "x".repeat(5000).getBytes(); exchange.sendResponseHeaders(200, text.length); exchange.getResponseBody().write(text); exchange.close();
        });
        server.createContext("/bad", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location", "/target"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var c = HookContext.lifecycle(HookEvents.TURN_END, "message"); var actions = new HookActions(root);
        try {
            var result = actions.execute(config(HookConfig.ActionType.HTTP, Map.of("url", base + "/ok", "body", "$MESSAGE"), Duration.ofSeconds(2)), c);
            assertTrue(result.ok()); assertEquals(4000, result.output().length()); assertEquals(List.of("POST:message"), seen);
            assertFalse(actions.execute(config(HookConfig.ActionType.HTTP, Map.of("url", base + "/bad"), Duration.ofSeconds(2)), c).ok());
            assertFalse(actions.execute(config(HookConfig.ActionType.HTTP, Map.of("url", base + "/redirect"), Duration.ofSeconds(2)), c).ok());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }
    @Test void actionPermissionRequiresApprovalAndAgentFailureCannotReject() {
        var count = new AtomicInteger();
        var command = config(HookConfig.ActionType.COMMAND, Map.of("command", "npm run format"), Duration.ofSeconds(1));
        var c = HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null);
        try (var engine = new HookEngine(List.of(command), (h, ctx) -> { count.incrementAndGet(); return HookAction.Result.success("ok"); }, () -> checker(PermissionMode.DEFAULT), null)) {
            assertFalse(engine.fire(c.event(), c).rejected()); assertEquals(0, count.get());
            assertTrue(engine.fire(c.event(), c, (tool, args) -> true).rejected()); assertEquals(1, count.get());
        }
        var agent = config(HookConfig.ActionType.AGENT, Map.of("prompt", "do it"), Duration.ofSeconds(1));
        try (var engine = new HookEngine(List.of(agent), new HookActions(root), null, null)) { assertFalse(engine.fire(c.event(), c).rejected()); }
        var dangerous = config(HookConfig.ActionType.COMMAND, Map.of("command", "rm -rf /"), Duration.ofSeconds(1));
        try (var engine = new HookEngine(List.of(dangerous), (h, ctx) -> { fail("dangerous command executed"); return null; }, () -> checker(PermissionMode.BYPASS), null)) {
            assertFalse(engine.fire(c.event(), c, (tool, args) -> true).rejected());
        }
    }
    @Test void timeoutTerminatesSpawnedProcessTree() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path marker = root.resolve("pid");
        String command = "\"" + java + "\" -cp \"" + Path.of("target/test-classes").toAbsolutePath() + "\" com.acode.hook.HookSleepProcess \"" + marker + "\"";
        if (new com.acode.tool.impl.ShellDetector().shellName().equals("cmd")) command = "call " + command;
        var result = new HookActions(root).execute(config(HookConfig.ActionType.COMMAND, Map.of("command", command), Duration.ofSeconds(2)), HookContext.lifecycle(HookEvents.TURN_START, null));
        assertFalse(result.ok());
        for (Path file : List.of(marker, Path.of(marker + ".child"))) {
            assertTrue(Files.exists(file), "process fixture started: " + file + " output=" + result.output());
            long pid = Long.parseLong(Files.readString(file));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "timed-out process still alive: " + pid);
        }
    }
    @Test void httpBodyTimeoutIsContainedAndLoggedByEngine() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 20); exchange.getResponseBody().write('a'); exchange.getResponseBody().flush();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var hook = config(HookConfig.ActionType.HTTP, Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/slow"), Duration.ofMillis(100));
        try (var engine = new HookEngine(List.of(hook), new HookActions(root), () -> checker(PermissionMode.BYPASS), null)) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(engine.fire(HookEvents.PRE_TOOL_USE, HookContext.lifecycle(HookEvents.PRE_TOOL_USE, null)).rejected()));
        } finally { release.countDown(); server.stop(0); }
    }
}
