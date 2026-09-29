package com.acode.ui;

import com.acode.CommandProcessor;
import com.acode.command.BuiltinCommands;
import com.acode.command.CommandContext;
import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.conversation.Conversation;
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ResizeMainLoopEndToEndTest {
    @TempDir Path directory;

    @Test
    void resizeEditSubmitNextReadAndQuitFinishWithinTenSeconds() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            var sessions = new SessionManager(new SessionStore(directory),
                    new Conversation("test", false, 4096, 2000));
            var registry = new CommandRegistry();
            BuiltinCommands.registerAll(registry);
            var inputs = new CopyOnWriteArrayList<String>();
            var processor = new CommandProcessor(f.tui, sessions, registry);
            processor.setInputFrame(new CommandProcessor.InputFrame() {
                public boolean statusOwnedInput() { return true; }
                public void draw() { fail("Unexpected legacy frame drawing"); }
                public void erase() { f.screen.submitted(); }
            });
            processor.setCommandDispatcher(new CommandDispatcher(registry,
                    args -> new CommandContext(args, null, null, null, null, sessions, directory, null, "test"),
                    inputs::add));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var loop = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try { processor.mainLoop(); loop.complete(null); }
                catch (Throwable e) { loop.completeExceptionally(e); }
            });
            f.send("中文abc");
            await(() -> f.view().screenText().contains("中文abc"), loop, deadline);
            var resized = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 20; i++) {
                    f.terminal.setSize(new Size(i % 2 == 0 ? 60 : 80, 24));
                    f.terminal.raise(Terminal.Signal.WINCH);
                }
            });
            f.send("\033[13;2udef");
            resized.get(remaining(deadline), TimeUnit.NANOSECONDS);
            f.send("\r");
            await(() -> inputs.size() == 1, loop, deadline);
            f.send("again\r");
            await(() -> inputs.size() == 2, loop, deadline);
            f.send("/quit\r");
            loop.get(remaining(deadline), TimeUnit.NANOSECONDS);
            assertEquals(List.of("中文abc\ndef", "again"), inputs);
            assertTrue(System.nanoTime() < deadline, "Main loop exceeded ten seconds");
        }
    }

    private static void await(BooleanSupplier ready, CompletableFuture<?> loop, long deadline) throws Exception {
        while (!ready.getAsBoolean()) {
            if (loop.isDone()) { loop.get(); fail("Main loop ended before expected transition"); }
            remaining(deadline);
            Thread.sleep(10);
        }
    }

    private static long remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        assertTrue(nanos > 0, "Main loop exceeded ten seconds");
        return nanos;
    }
}
