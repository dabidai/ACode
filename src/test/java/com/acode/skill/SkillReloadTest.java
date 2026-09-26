package com.acode.skill;

import com.acode.command.*;
import com.acode.conversation.Conversation;
import com.acode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.acode.skill.SkillRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillReloadTest {
    @TempDir Path root;
    @Test void lookupWaitsUntilCommandsAndPromptArePublished() throws Exception {
        write(root, "a.md", definition("a", "", "a"));
        var repo = new SkillRepository(root, null, s -> {});
        var commands = new CommandRegistry(); BuiltinCommands.registerAll(commands);
        var runtime = new SkillRuntime(repo, new ToolRegistry(), new Conversation("default", false, 1000, 10000));
        var publishing = new CountDownLatch(1); var release = new CountDownLatch(1); var pause = new AtomicBoolean();
        var manager = new SkillManager(repo, runtime, commands, () -> {
            if (pause.get()) {
                publishing.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, s -> {});
        manager.reload(); assertNotNull(commands.find("a"));
        var candidates = new java.util.ArrayList<org.jline.reader.Candidate>();
        var completer = new com.acode.ui.SlashCompleter(commands);
        var parsed = new org.jline.reader.impl.DefaultParser().parse("/", 1);
        completer.complete(null, parsed, candidates);
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/commit") && c.descr().endsWith(" [skill]")));
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/test") && c.descr().endsWith(" [skill]")));
        write(root, "b.md", definition("b", "", "b")); pause.set(true);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reload = pool.submit(manager::reload);
            assertTrue(publishing.await(2, TimeUnit.SECONDS));
            var lookup = pool.submit(() -> repo.load("b"));
            var command = pool.submit(() -> commands.find("b"));
            try {
                assertThrows(TimeoutException.class, () -> lookup.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> command.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            reload.get(2, TimeUnit.SECONDS); assertTrue(lookup.get().isPresent()); assertNotNull(command.get());
        }
        assertNotNull(commands.find("review"));
        pause.set(false);
        Files.move(root.resolve(".acode/skills/a.md"), root.resolve(".acode/skills/renamed.md"));
        write(root, "renamed.md", definition("renamed", "", "renamed"));
        write(root, "commit.md", definition("commit", "", "project commit"));
        manager.reload(); candidates.clear(); completer.complete(null, parsed, candidates);
        assertNull(commands.find("a")); assertNotNull(commands.find("renamed"));
        assertFalse(candidates.stream().anyMatch(c -> c.value().equals("/a")));
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/renamed")));
        assertEquals("project commit", repo.load("commit").orElseThrow().body());
        assertEquals("useful [skill]", commands.find("commit").description());
        assertTrue(repo.indexText().contains("commit: useful"));
    }
}
