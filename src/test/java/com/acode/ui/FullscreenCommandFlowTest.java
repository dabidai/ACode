package com.acode.ui;

import com.acode.CommandProcessor;
import com.acode.command.*;
import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FullscreenCommandFlowTest {
    @TempDir Path directory;

    private static void await(BooleanSupplier ready, CompletableFuture<?> worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            if (worker.isDone()) worker.get();
            if (System.nanoTime() > deadline) fail("Expected UI transition was not observed");
            Thread.sleep(10);
        }
    }

    @Test void realMainLoopDispatchesChatResumeMenuCancelCopyAndQuit() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            var store = new SessionStore(directory);
            var conversation = new Conversation("test", false, 4096, 2000);
            var sessions = new SessionManager(store, conversation);
            // Temporary JSONL fixture only. No real project sessions or database are touched.
            sessions.recorder().append(ChatMessage.of(ChatMessage.Role.USER, "saved question"));
            sessions.recorder().append(ChatMessage.of(ChatMessage.Role.ASSISTANT, "saved answer"));
            sessions.closeSession();
            Path fixture = store.resolve(store.list().getFirst().id());
            String originalFile = Files.readString(fixture);
            var render = new RenderContext(new AppConfig()); render.attachTui(f.tui);
            sessions.attachUi(f.output, render, f.tui);
            AtomicInteger loads = new AtomicInteger();
            sessions.setLoader(session -> {
                sessions.renderLoaded("恢复", session.id(), session.messages()); loads.incrementAndGet();
            });
            AtomicInteger submitted = new AtomicInteger();
            var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
            AtomicReference<String> clipboard = new AtomicReference<>();
            var ui = new TerminalUIController(f.output, render, text -> {}, enabled -> {},
                    () -> new UIController.ContextUsage(0, 0),
                    (entries, title) -> SelectionMenu.of(entries, title, 0).select(f.screen, f.terminal.writer(),
                            new TerminalMenuKeySource(f.terminal.reader())),
                    () -> { f.screen.clearScreen(f.terminal.writer()); f.output.clear(); f.screen.refresh(); },
                    () -> null, () -> {});
            ui.setClipboard(new ClipboardService(clipboard::set)); ui.setLatestReply(() -> "saved answer");
            var registry = new CommandRegistry(); BuiltinCommands.registerAll(registry);
            var processor = new CommandProcessor(f.tui, sessions, registry);
            processor.setInputFrame(new CommandProcessor.InputFrame() {
                public boolean statusOwnedInput() { return true; }
                public void draw() { fail("Full-screen mode must not enter legacy frame drawing"); }
                public void erase() { f.screen.submitted(); }
            });
            processor.setCommandDispatcher(new CommandDispatcher(registry,
                    args -> new CommandContext(args, ui, null, null, null, sessions, directory, null, "test"),
                    text -> {
                        inputs.add(text);
                        submitted.incrementAndGet(); f.output.appendLine("● " + text);
                        var stream = new StreamPrinter(f.output, f.screen, f.terminal.writer(), false);
                        stream.onDelta("answer to " + text); stream.finishTurn();
                    }));
            CompletableFuture<Void> loop = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> { try { processor.mainLoop(); loop.complete(null); } catch (Throwable e) { loop.completeExceptionally(e); } });
            f.send("he");
            await(() -> f.view().screenText().contains("> he"), loop);
            var resize = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 20; i++) {
                    f.terminal.setSize(new org.jline.terminal.Size(i % 2 == 0 ? 60 : 80, 24));
                    f.terminal.raise(org.jline.terminal.Terminal.Signal.WINCH);
                }
            });
            f.send("llo");
            resize.get(5, TimeUnit.SECONDS);
            f.send("\r");
            await(() -> f.view().screenText().contains("answer to hello"), loop);
            f.send("/resume\r");
            await(() -> f.view().screenText().contains("Esc 取消"), loop);
            f.send("\r");
            await(() -> loads.get() == 1 && f.view().screenText().contains("saved answer"), loop);
            assertFalse(f.view().screenText().contains("answer to hello"));
            assertEquals(1, f.view().screenText().lines().filter("[default]"::equals).count());
            f.send("/resume\r");
            await(() -> f.view().screenText().contains("Esc 取消"), loop);
            f.send("\033");
            await(() -> f.view().screenText().contains("已取消"), loop);
            assertEquals(1, loads.get()); assertTrue(f.view().screenText().contains("saved answer"));
            f.send("/copy\r");
            await(() -> "saved answer".equals(clipboard.get()), loop);
            f.send("/clear\r");
            await(() -> f.output.lines().equals(List.of("（已清空）")), loop);
            f.send("again\r");
            await(() -> f.view().screenText().contains("answer to again"), loop);
            assertEquals(2, submitted.get());
            assertEquals(List.of("hello", "again"), inputs);
            assertFalse(f.view().screenText().contains("saved answer"));
            f.send("/quit\r"); loop.get(5, TimeUnit.SECONDS);
            f.screen.close();
            assertEquals(originalFile, Files.readString(fixture));
            assertTrue(f.bytes.toString(java.nio.charset.StandardCharsets.UTF_8).endsWith("\033[?1049l"));
        }
    }

    @Test void copyCommandValidatesArgumentsAndReportsEmptyOrUnavailableClipboard() {
        OutputPane output = new OutputPane();
        var render = new RenderContext(new AppConfig());
        AtomicInteger writes = new AtomicInteger(); AtomicReference<String> copied = new AtomicReference<>();
        var ui = new TerminalUIController(output, render, text -> fail("Copy must not submit a model request"), b -> {},
                () -> new UIController.ContextUsage(0, 0), (entries, title) -> -1, () -> {}, () -> null, () -> {});
        ui.setClipboard(new ClipboardService(text -> { writes.incrementAndGet(); copied.set(text); }));
        var registry = new CommandRegistry(); BuiltinCommands.registerAll(registry);
        var dispatcher = new CommandDispatcher(registry,
                args -> new CommandContext(args, ui, null, null, null, null, directory, null, "test"), text -> fail("No chat"));
        assertEquals(CommandResult.CONTINUE, dispatcher.dispatch("/copy"));
        assertEquals("没有可复制的回复", output.lines().getLast()); assertEquals(0, writes.get());
        dispatcher.dispatch("/copy invalid");
        assertEquals("用法：/copy [all]", output.lines().getLast()); assertEquals(0, writes.get());
        output.clear(); output.append("\033[31mfirst\033[0m\n\nsecond");
        dispatcher.dispatch("/copy all");
        assertEquals("first\n\nsecond", copied.get()); assertEquals(1, writes.get());
        ui.setLatestReply(() -> "**raw markdown**"); dispatcher.dispatch("/copy");
        assertEquals("**raw markdown**", copied.get());
        ui.setClipboard(new ClipboardService(text -> { throw new IllegalStateException("busy"); }));
        dispatcher.dispatch("/copy");
        assertEquals("复制失败：系统剪贴板不可用", output.lines().getLast());
    }
}
