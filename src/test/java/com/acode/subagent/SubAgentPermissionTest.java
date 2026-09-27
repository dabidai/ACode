package com.acode.subagent;

import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.acode.subagent.SubAgentTestSupport.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentPermissionTest {
    @TempDir Path root;
    @Test void childCannotEscalateOrInheritSessionApproval() {
        var parent = checker(root, PermissionMode.DEFAULT);
        var tool = tool("WriteFile", Permission.WRITE, new AtomicInteger());
        var input = JsonNodeFactory.instance.objectNode().put("file_path", "ok.txt");
        parent.addAllowAlwaysRule("WriteFile", "ok.txt");
        assertEquals(PermissionMode.Decision.ALLOW, parent.check(tool, input).decision());
        var child = parent.child(PermissionMode.BYPASS);
        assertEquals(PermissionMode.DEFAULT, child.mode());
        assertEquals(PermissionMode.Decision.ASK, child.check(tool, input).decision());
        assertEquals(parent.ruleLayers(), child.ruleLayers());
    }
    @Test void explicitDispatchScopeAllowsOnlyMatchingCalls() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("WriteFile", Permission.WRITE, count));
        for (boolean authorized : List.of(false, true)) {
            var provider = scripted(List.of(List.of(toolUse("w", "WriteFile", JsonNodeFactory.instance.objectNode().put("file_path", "ok.txt")), complete()), List.of(delta("done"), complete())));
            var runner = new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.DEFAULT), null);
            runner.run(definition(List.of(), List.of(), 20, PermissionMode.BYPASS), "task", "label", null, root, null,
                    call -> authorized && call.name().equals("WriteFile") && call.input().path("file_path").asText().equals("ok.txt"));
            assertEquals(authorized ? 1 : 0, count.get());
        }
    }
    @Test void blacklistAndSandboxStillDenyUnderBypass() {
        var count = new AtomicInteger(); var tools = new ToolRegistry().register(tool("Bash", Permission.EXEC, count));
        var provider = scripted(List.of(List.of(toolUse("b", "Bash", JsonNodeFactory.instance.objectNode().put("command", "rm -rf /")), complete()), List.of(delta("denied"), complete())));
        new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.BYPASS), null)
                .run(definition(List.of(), List.of(), 20, PermissionMode.BYPASS), "task", "label", null, root, null, call -> true);
        assertEquals(0, count.get());
        var child = checker(root, PermissionMode.BYPASS).child(PermissionMode.BYPASS);
        assertEquals(PermissionMode.Decision.DENY, child.check(tool("WriteFile", Permission.WRITE, count),
                JsonNodeFactory.instance.objectNode().put("file_path", root.getRoot().resolve("acode-outside-sandbox.txt").toString())).decision());
    }
    @Test void extraRootsArePreserved() throws Exception {
        Path project = Files.createDirectories(root.resolve("project")), memory = Files.createDirectories(root.resolve("memory"));
        String oldTemp = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", Files.createDirectories(root.resolve("temporary")).toString());
        try {
        var parent = new PermissionChecker(PermissionMode.DEFAULT, project, new RuleEngine(root.resolve("u"), root.resolve("p"), root.resolve("l")), List.of(memory));
        var input = JsonNodeFactory.instance.objectNode().put("file_path", memory.resolve("notes.md").toString());
        assertEquals(PermissionMode.Decision.DENY, checker(project, PermissionMode.DEFAULT).check(tool("ReadFile", Permission.READ, new AtomicInteger()), input).decision(), "control: memory must not already be inside a default root");
        assertEquals(PermissionMode.Decision.ALLOW, parent.child(PermissionMode.DEFAULT).check(tool("ReadFile", Permission.READ, new AtomicInteger()), input).decision());
        } finally { System.setProperty("java.io.tmpdir", oldTemp); }
    }
    @Test void scopeDoesNotAuthorizeDifferentArgumentsOrTools() {
        var writes = new AtomicInteger(); var edits = new AtomicInteger();
        var tools = new ToolRegistry().register(tool("WriteFile", Permission.WRITE, writes)).register(tool("EditFile", Permission.WRITE, edits));
        var json = JsonNodeFactory.instance;
        var provider = scripted(List.of(List.of(
                toolUse("allowed", "WriteFile", json.objectNode().put("file_path", "ok.txt")),
                toolUse("other-path", "WriteFile", json.objectNode().put("file_path", "other.txt")),
                toolUse("other-tool", "EditFile", json.objectNode().put("file_path", "ok.txt")), complete()), List.of(delta("done"), complete())));
        new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.DEFAULT), null)
                .run(null, "task", "label", null, root, null, call -> call.name().equals("WriteFile") && call.input().path("file_path").asText().equals("ok.txt"));
        assertEquals(1, writes.get()); assertEquals(0, edits.get());
        var results = provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream()).filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
        assertEquals(List.of(false, true, true), results.stream().map(ToolResultBlock::isError).toList());
    }
    @Test void ruleDenyWinsOverBypassAndExplicitDelegation() throws Exception {
        Files.writeString(root.resolve("local.yaml"), "rules:\n  - rule: WriteFile(blocked.txt)\n    effect: deny\n");
        var count = new AtomicInteger(); var approvals = new AtomicInteger();
        var tools = new ToolRegistry().register(tool("WriteFile", Permission.WRITE, count));
        var provider = scripted(List.of(List.of(toolUse("w", "WriteFile", JsonNodeFactory.instance.objectNode().put("file_path", "blocked.txt")), complete()), List.of(delta("done"), complete())));
        new SubAgentRunner(provider, parent(), tools, () -> checker(root, PermissionMode.BYPASS), null)
                .run(null, "task", "label", null, root, null, call -> { approvals.incrementAndGet(); return true; });
        assertEquals(0, count.get()); assertEquals(0, approvals.get());
        assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m -> m.blocks().stream())
                .anyMatch(b -> b instanceof ToolResultBlock r && r.isError() && r.content().contains("规则拒绝")));
    }
    @Test void allParentAndChildModesRespectBothPermissionMatrices() {
        for (PermissionMode parentMode : PermissionMode.values()) for (PermissionMode childMode : PermissionMode.values()) {
            var child = checker(root, parentMode).child(childMode);
            for (Permission permission : Permission.values()) {
                var input = JsonNodeFactory.instance.objectNode().put("file_path", "ordinary.txt");
                var expected = parentMode.decide(permission) == PermissionMode.Decision.ALLOW && childMode.decide(permission) == PermissionMode.Decision.ALLOW
                        ? PermissionMode.Decision.ALLOW : PermissionMode.Decision.ASK;
                assertEquals(expected, child.check(tool("Probe", permission, new AtomicInteger()), input).decision(), parentMode + "/" + childMode + "/" + permission);
            }
        }
    }
}
