package com.acode.team;

import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.FakeProvider;
import com.acode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TeamSpawnFailureTest {
    @TempDir Path root;
    @Test void failedAllocationNeverRemovesExistingWorktree() {
        var removes = new AtomicInteger();
        try (var session = new TeamSession(root, FakeProvider.streaming("done"), new Conversation("test", false, 1024, 32000),
                new ToolRegistry(), () -> new PermissionChecker(PermissionMode.DEFAULT, root,
                    new RuleEngine(root.resolve("u"), root.resolve("p"), root.resolve("l"))), () -> null,
                new TeamSession.Worktrees() {
                    public Path create(String name) throws Exception { throw new java.io.IOException("already exists"); }
                    public void verifyRemovable(String name) {}
                    public void remove(String name) { removes.incrementAndGet(); }
                }, message -> {})) {
            session.create("team", null);
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isError());
            assertTrue(session.manager().find("team").orElseThrow().members().isEmpty());
            assertEquals(0, removes.get());
        }
    }

    @Test void failureAfterRegistrationRollsBackAndAllowsSameNameAgain() throws Exception {
        Path worktree = root.resolve("worktree");
        try (var session = new TeamSession(root, FakeProvider.streaming("done"), new Conversation("test", false, 1024, 32000),
                new ToolRegistry(), () -> new PermissionChecker(PermissionMode.DEFAULT, root,
                    new RuleEngine(root.resolve("u"), root.resolve("p"), root.resolve("l"))), () -> null,
                new TeamSession.Worktrees() {
                    public Path create(String name) throws Exception { return Files.createDirectory(worktree); }
                    public void verifyRemovable(String name) {}
                    public void remove(String name) throws Exception { Files.delete(worktree); }
                }, message -> {})) {
            session.create("team", null);
            session.setHooks(() -> { throw new IllegalStateException("injected factory failure"); });
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isError());
            assertTrue(session.manager().find("team").orElseThrow().members().isEmpty());
            assertFalse(Files.exists(worktree));
            session.setHooks(() -> null);
            assertTrue(session.spawn("team", "alice", "work", null, null, false).isSuccess());
        }
    }
}
