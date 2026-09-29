package com.acode.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TeamManagerTest {
    @TempDir Path root;

    private static TeammateInfo member(String name) {
        return new TeammateInfo(name, "agent-" + name, null, null, null, null, null, false);
    }

    @Test void createsCompleteConfigurationWithoutRestoringPreviousSession() throws Exception {
        var manager = new TeamManager(root, () -> 1234L);
        Team team = manager.create("team", "lead-1", "Test team");
        assertEquals(root.resolve(".acode/teams/team/config.json"), team.configPath());
        var json = new ObjectMapper().readTree(team.configPath().toFile());
        assertEquals("team", json.path("name").textValue());
        assertEquals("lead-1", json.path("leadAgentID").textValue());
        assertEquals("Test team", json.path("description").textValue());
        assertTrue(json.path("members").isArray());
        assertEquals(1234L, json.path("createdAt").longValue());
        assertEquals(team, manager.readConfiguration("team").orElseThrow());
        var restarted = new TeamManager(root);
        assertTrue(restarted.list().isEmpty());
        assertEquals(team, restarted.readConfiguration("team").orElseThrow());
        assertTrue(restarted.list().isEmpty(), "inspection must not restore a team");
        assertThrows(IllegalArgumentException.class, () -> restarted.register("team", member("alice")));
    }

    @Test void avoidsBothLiveAndDiskCollisionsWithoutOverwriting() throws Exception {
        var manager = new TeamManager(root);
        var first = manager.create("team", "lead", null);
        assertEquals("team", first.name());
        assertEquals("team-2", manager.create("team", "lead", null).name());
        var third = new TeamManager(root).create("team", "other-lead", null);
        assertEquals("team-3", third.name());
        assertTrue(Files.exists(third.configPath()));
        assertEquals("lead", manager.readConfiguration("team").orElseThrow().leadAgentID());
        Files.writeString(root.resolve(".acode/teams/occupied"), "keep");
        assertEquals("occupied-2", manager.create("occupied", "lead", null).name());
        assertEquals("keep", Files.readString(root.resolve(".acode/teams/occupied")));
    }

    @Test void suffixKeepsMaximumLengthNamesValid() {
        var manager = new TeamManager(root);
        String name = "a".repeat(64);
        manager.create(name, "lead", null);
        String second = manager.create(name, "lead", null).name();
        assertEquals("a".repeat(62) + "-2", second);
        assertEquals(64, second.length());
    }

    @Test void updatesAndRemovesMembersWithImmutableSnapshots() {
        var manager = new TeamManager(root);
        Team empty = manager.create("team", "lead", null);
        Team withAlice = manager.register("team", member("alice"));
        assertTrue(empty.members().isEmpty());
        assertTrue(withAlice.members().getFirst().active());
        assertThrows(UnsupportedOperationException.class, () -> withAlice.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> manager.list().clear());
        manager.setActive("team", "agent-alice", false);
        assertFalse(manager.member("team", "alice").orElseThrow().active());
        assertTrue(withAlice.members().getFirst().active(), "old snapshot must not change");
        manager.setActive("team", "alice", true);
        assertTrue(manager.readConfiguration("team").orElseThrow().members().getFirst().active());
        manager.removeMember("team", "agent-alice");
        assertTrue(manager.member("team", "alice").isEmpty());
        assertTrue(manager.readConfiguration("team").orElseThrow().members().isEmpty());
        assertEquals(manager.find("team").orElseThrow(), manager.removeMember("team", "missing"));
    }

    @Test void persistsAllRosterFieldsIncludingNullActiveAndApproval() throws Exception {
        var manager = new TeamManager(root);
        var team = manager.create("team", "lead", null);
        var info = new TeammateInfo("alice", "agent-a", "Explore", "test-model", root.resolve("work"),
                TeammateInfo.BackendType.IN_PROCESS, null, true);
        manager.register("team", info);
        assertEquals(info, manager.readConfiguration("team").orElseThrow().members().getFirst());
        var row = new ObjectMapper().readTree(team.configPath().toFile()).path("members").get(0);
        for (String key : List.of("name", "agentID", "agentType", "model", "worktreePath", "backendType", "isActive", "planModeRequired"))
            assertTrue(row.has(key), key);
        assertEquals("in-process", row.path("backendType").textValue());
        assertTrue(row.path("isActive").isNull());
        assertTrue(info.active());
    }

    @Test void rejectsDuplicateNamesIdsAndAmbiguousAddressing() {
        var manager = new TeamManager(root);
        manager.create("team", "lead", null);
        manager.register("team", member("alice"));
        assertThrows(IllegalArgumentException.class, () -> manager.register("team", member("alice")));
        assertThrows(IllegalArgumentException.class, () -> manager.register("team",
                new TeammateInfo("bob", "agent-alice", null, null, null, null, true, false)));
        assertThrows(IllegalArgumentException.class, () -> manager.register("team", member("agent-alice")));
        assertEquals(1, manager.find("team").orElseThrow().members().size());
    }

    @Test void corruptedConfigurationReturnsEmptyWithoutDestroyingLiveState() throws Exception {
        var warnings = new java.util.ArrayList<String>();
        var manager = new TeamManager(root, System::currentTimeMillis, warnings::add);
        var team = manager.create("team", "lead", null);
        manager.register("team", member("alice"));
        for (String invalid : List.of("{", "null", "[]", "{}",
                "{\"name\":\"team\",\"leadAgentID\":\"lead\",\"members\":[{}],\"createdAt\":1}")) {
            Files.writeString(team.configPath(), invalid);
            assertTrue(manager.readConfiguration("team").isEmpty());
            assertEquals(invalid, Files.readString(team.configPath()));
            assertEquals(1, manager.find("team").orElseThrow().members().size());
        }
        assertEquals(5, warnings.size());
        assertTrue(warnings.stream().allMatch(text -> text.contains("按空团队处理")));
        assertTrue(manager.readConfiguration("missing").isEmpty());
    }

    @Test void failedWriteDoesNotPublishPartiallyUpdatedRoster() throws Exception {
        var manager = new TeamManager(root);
        var team = manager.create("team", "lead", null);
        Files.delete(team.configPath()); // Only this test's own temporary config.
        Files.createDirectory(team.configPath());
        Files.writeString(team.configPath().resolve("sentinel"), "keep");
        assertThrows(UncheckedIOException.class, () -> manager.register("team", member("alice")));
        assertTrue(manager.find("team").orElseThrow().members().isEmpty());
        assertEquals("keep", Files.readString(team.configPath().resolve("sentinel")));
        try (var files = Files.list(team.configPath().getParent())) {
            assertEquals(List.of("config.json", "mailbox", "tasks.json", "transcripts"), files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test void rejectsPathTraversalAndWindowsReservedNamesBeforeWriting() {
        var manager = new TeamManager(root);
        for (String invalid : List.of("", " ", ".", "..", "../outside", "a/b", "a\\b", "NUL", "con.txt", "bad.", "a".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> manager.create(invalid, "lead", null), invalid);
            assertThrows(IllegalArgumentException.class, () -> new TeammateInfo(invalid, "id", null, null, null, null, null, false));
        }
        assertFalse(Files.exists(root.resolve(".acode")));
    }

    @Test void rejectsLinkedTeamDirectory() throws Exception {
        Path external = Files.createDirectory(root.resolve("external"));
        Files.createDirectory(root.resolve(".acode"));
        try { Files.createSymbolicLink(root.resolve(".acode/teams"), external); }
        catch (Exception e) { assumeTrue(false, "Link creation unavailable: " + e.getMessage()); }
        assertThrows(UncheckedIOException.class, () -> new TeamManager(root).create("team", "lead", null));
        try (var files = Files.list(external)) { assertEquals(0, files.count()); }
    }

    @Test void concurrentRegistrationsNeverLoseRosterEntries() throws Exception {
        var manager = new TeamManager(root);
        for (int round = 0; round < 3; round++) {
            String name = manager.create("team", "lead", null).name();
            var ready = new CountDownLatch(10);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new java.util.ArrayList<Future<?>>();
                for (int i = 0; i < 10; i++) {
                    int id = i;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        manager.register(name, member("member-" + id));
                        return null;
                    }));
                }
                try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
                finally { start.countDown(); }
                for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            }
            var members = manager.readConfiguration(name).orElseThrow().members();
            assertEquals(10, members.size());
            assertEquals(10, members.stream().map(TeammateInfo::agentID).distinct().count());
        }
    }

    @Test void rejectsWindowsJunctionEvenWhenItPointsInsideProject() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path target = Files.createDirectory(root.resolve("other-directory"));
        Files.createDirectory(root.resolve(".acode"));
        Path link = root.resolve(".acode/teams");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
            assertThrows(UncheckedIOException.class, () -> new TeamManager(root).create("team", "lead", null));
            try (var files = Files.list(target)) { assertEquals(0, files.count()); }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            // Delete just the test's directory entry, never recurse through the junction.
            Files.deleteIfExists(link);
        }
    }

    @Test void concurrentManagersReserveDifferentDirectoriesAtomically() throws Exception {
        var first = new TeamManager(root);
        var second = new TeamManager(root);
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Team> a = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return first.create("team", "a", null); });
            Future<Team> b = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return second.create("team", "b", null); });
            assertEquals(Set.of("team", "team-2"), Set.of(a.get(10, TimeUnit.SECONDS).name(), b.get(10, TimeUnit.SECONDS).name()));
        }
    }
}
