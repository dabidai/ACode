package com.acode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TeamTaskStoreTest {
    @TempDir Path directory;
    private TeamTaskStore store() { return new TeamTaskStore(directory, message -> fail(message)); }

    @Test void dependenciesBlockClaimsAndBothDependencyDirectionsAgree() {
        var store = store();
        var a = store.create("first", "description", null, null);
        var b = store.create("second", null, List.of(a.id()), null);
        assertEquals("1", a.id()); assertEquals("2", b.id());
        assertTrue(b.blocked());
        assertEquals(List.of(b.id()), store.get(a.id()).blocks());
        assertEquals("任务 2 存在未完成的依赖，不能认领", assertThrows(IllegalArgumentException.class,
                () -> store.update(b.id(), "bob", "in_progress", null, null)).getMessage());
        store.update(a.id(), "alice", "in_progress", null, null);
        assertThrows(IllegalArgumentException.class, () -> store.update(a.id(), "bob", "completed", null, null));
        store.update(a.id(), "alice", "completed", null, null);
        assertFalse(store.get(b.id()).blocked());
        store.update(b.id(), "bob", "in_progress", null, null);
        assertEquals("bob", store.get(b.id()).owner());
        assertEquals(store.list(), new TeamTaskStore(directory).list());
        var c = store.create("third", null, null, null);
        var d = store.create("fourth", null, null, List.of(c.id()));
        assertEquals(List.of(d.id()), store.get(c.id()).blockedBy());
    }

    @Test void failedGraphUpdateAndFailedCombinedClaimLeaveFileUnchanged() throws Exception {
        var store = store();
        var a = store.create("a", null, null, null);
        var b = store.create("b", null, List.of(a.id()), null);
        String original = Files.readString(directory.resolve("tasks.json"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> store.update(a.id(), "alice", null, List.of(b.id()), null)).getMessage().startsWith("存在循环依赖："));
        assertEquals("任务不能依赖自身", assertThrows(IllegalArgumentException.class,
                () -> store.update(a.id(), "alice", null, List.of(a.id()), null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.create("bad", null, List.of("999"), null));
        assertEquals(original, Files.readString(directory.resolve("tasks.json")));
        assertThrows(IllegalArgumentException.class,
                () -> store.update(a.id(), "alice", "in_progress", List.of(b.id()), null));
        assertEquals(original, Files.readString(directory.resolve("tasks.json")));
        assertEquals("3", store.create("c", null, null, null).id(), "failed create must not consume IDs");
    }

    @Test void concurrentClaimsAcrossStoreInstancesHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = store().create("task", null, null, null).id();
            var barrier = new CyclicBarrier(2);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.Callable<Boolean> claim = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try { store().update(id, Thread.currentThread().toString(), "in_progress", null, null); return true; }
                    catch (IllegalArgumentException conflict) { assertEquals("任务 " + id + " 已被认领", conflict.getMessage()); return false; }
                };
                var a = pool.submit(claim); var b = pool.submit(claim);
                assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            }
            assertEquals("in_progress", store().get(id).status());
        }
    }

    @Test void memberRemovalRollsBackOnlyItsUnfinishedTasks() {
        var manager = new TeamManager(directory);
        var team = manager.create("team", "lead", null);
        manager.register("team", new TeammateInfo("alice", "agent-a", null, null, null, null, false, false));
        var store = new TeamTaskStore(team.configPath().getParent());
        String a = store.create("a", null, null, null).id(), b = store.create("b", null, null, null).id();
        String c = store.create("c", null, null, null).id();
        store.update(a, "agent-a", "in_progress", null, null);
        store.update(b, "agent-a", "in_progress", null, null);
        store.update(b, "agent-a", "completed", null, null);
        store.update(c, "agent-b", "in_progress", null, null);
        manager.removeMember("team", "alice");
        assertTrue(manager.member("team", "alice").isEmpty());
        assertEquals("pending", store.get(a).status()); assertNull(store.get(a).owner());
        assertEquals("completed", store.get(b).status());
        assertEquals("agent-b", store.get(c).owner());
        store.update(a, "agent-b", "in_progress", null, null);
        assertEquals("agent-b", store.get(a).owner());
    }

    @Test void corruptFileIsReadableAsEmptyButNeverOverwritten() throws Exception {
        var warnings = new ArrayList<String>();
        var store = new TeamTaskStore(directory, warnings::add);
        Files.writeString(directory.resolve("tasks.json"), "[");
        assertTrue(store.list().isEmpty()); assertEquals(1, warnings.size());
        assertThrows(UncheckedIOException.class, () -> store.create("task", null, null, null));
        assertThrows(UncheckedIOException.class, () -> store.rollbackOwner("alice"));
        assertEquals("[", Files.readString(directory.resolve("tasks.json")));
    }

    @Test void dependenciesCannotBeAddedToClaimedTasks() {
        var store = store();
        var a = store.create("a", null, null, null);
        var b = store.create("b", null, null, null);
        store.update(a.id(), "alice", "in_progress", null, null);
        assertThrows(IllegalArgumentException.class, () -> store.update(b.id(), "bob", null, null, List.of(a.id())));
        assertTrue(store.get(a.id()).blockedBy().isEmpty());
    }

    @Test void threeNodeCycleIsRejectedWithoutChangingDependencies() {
        var store = store();
        var a = store.create("a", null, null, null);
        var b = store.create("b", null, List.of(a.id()), null);
        var c = store.create("c", null, List.of(b.id()), null);
        assertEquals("存在循环依赖：1 -> 3 -> 2 -> 1", assertThrows(IllegalArgumentException.class,
                () -> store.update(a.id(), "alice", null, List.of(c.id()), null)).getMessage());
        assertTrue(store.get(a.id()).blockedBy().isEmpty());
    }
}
