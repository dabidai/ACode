package com.acode.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.Size;
import org.jline.reader.Reference;
import org.jline.reader.LineReader;
import org.jline.utils.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResizeAwareLineReaderConcurrencyTest {
    @TempDir Path temp;

    @Test
    void editingSurvivesSignalsTinyWindowAndNextReadInEveryMode() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (var f = new EditorFixture(mode)) {
                var result = f.read();
                f.sendAndAwait("中文abc\033[13;2udef");
                var signals = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < 20; i++) {
                        f.terminal.setSize(new Size(i % 2 == 0 ? 60 : 80,
                                i % 3 == 0 ? 5 : 24));
                        f.terminal.raise(Terminal.Signal.WINCH);
                    }
                    f.terminal.setSize(new Size(80, 24));
                    f.terminal.raise(Terminal.Signal.WINCH);
                    f.terminal.raise(Terminal.Signal.WINCH);
                    f.terminal.raise(Terminal.Signal.CONT);
                });
                f.sendAndAwait("末尾");
                signals.get(10, TimeUnit.SECONDS);
                assertTrue(f.reader.wasResizedDuringLastRead());
                if (mode == 1) assertEquals(8, Status.getStatus(f.terminal, false).size());
                f.send("\r");
                assertEquals("中文abc\ndef末尾", result.get(10, TimeUnit.SECONDS));
                var next = f.read();
                f.sendAndAwait("again");
                assertFalse(f.reader.wasResizedDuringLastRead(), "resize flag leaked into next read, mode=" + mode);
                f.send("\r");
                assertEquals("again", next.get(10, TimeUnit.SECONDS));
                assertFalse(f.output.toString(StandardCharsets.UTF_8).contains("\033[6n"));
                assertFalse(f.output.toString(StandardCharsets.UTF_8).contains("\033[3J"));
            }
        }
    }

    @Test
    void footerCanWaitForAnotherRedrawAndThrowWithoutBlockingInput() throws Exception {
        try (var f = new EditorFixture(0)) {
            var callbackResult = new CompletableFuture<Void>();
            var result = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(f.reader.readLine(() -> "> ", () -> {
                        try {
                            var redraw = new CompletableFuture<Void>();
                            Thread.ofVirtual().start(() -> {
                                try { f.reader.redisplay(); redraw.complete(null); }
                                catch (Throwable e) { redraw.completeExceptionally(e); }
                            });
                            redraw.get(3, TimeUnit.SECONDS);
                            callbackResult.complete(null);
                        } catch (Throwable e) { callbackResult.completeExceptionally(e); }
                        throw new IllegalStateException("intentional footer failure");
                    }));
                } catch (Throwable e) { result.completeExceptionally(e); }
            });
            f.sendAndAwait("abc");
            var signal = CompletableFuture.runAsync(() -> {
                f.terminal.setSize(new Size(60, 20));
                f.terminal.raise(Terminal.Signal.WINCH);
            });
            callbackResult.get(5, TimeUnit.SECONDS);
            signal.get(5, TimeUnit.SECONDS);
            f.send("def\r");
            assertEquals("abcdef", result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void frameCleanupWaitsForInFlightResizeBeforeClearingSuppliers() throws Exception {
        try (var f = new EditorFixture(1)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            f.frameMode = () -> {
                if (Thread.currentThread().getName().equals("held-resize")) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("resize not released"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return "default";
            };
            var result = f.read();
            f.sendAndAwait("abc");
            var signal = new CompletableFuture<Void>();
            Thread.ofVirtual().name("held-resize").start(() -> {
                try {
                    f.terminal.setSize(new Size(60, 20));
                    f.terminal.raise(Terminal.Signal.WINCH);
                    signal.complete(null);
                } catch (Throwable e) { signal.completeExceptionally(e); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.send("\r");
                assertFalse(result.isDone(), "input must wait for in-flight frame redraw");
            } finally { release.countDown(); }
            assertEquals("abc", result.get(5, TimeUnit.SECONDS));
            signal.get(5, TimeUnit.SECONDS);
            var next = f.read();
            f.sendAndAwait("next"); f.send("\r");
            assertEquals("next", next.get(5, TimeUnit.SECONDS));
        }
    }

    private static final class EditorFixture implements AutoCloseable {
        final PipedInputStream input = new PipedInputStream();
        final PipedOutputStream keys = new PipedOutputStream(input);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Terminal terminal = TerminalBuilder.builder()
                .system(false).type("xterm-256color").encoding(StandardCharsets.UTF_8)
                .streams(input, output).size(new Size(80, 24)).build();
        final ResizeAwareLineReader reader;
        final ScreenRenderer screen;
        final int mode;
        final Semaphore marks = new Semaphore(0);
        Supplier<String> frameMode = () -> "default";

        EditorFixture(int mode) throws Exception {
            this.mode = mode;
            screen = mode == 2 ? new ScreenRenderer(terminal, new OutputPane()) : null;
            if (screen != null) screen.open();
            reader = new ResizeAwareLineReader(screen == null ? terminal : EditorTerminal.wrap(terminal), "edge-test");
            reader.screen(screen);
            reader.getWidgets().put("mark", () -> { marks.release(); return true; });
            reader.getWidgets().put("newline", () -> { reader.getBuffer().write("\n"); return true; });
            var keyMap = reader.getKeyMaps().get(LineReader.MAIN);
            keyMap.bind(new Reference("mark"), "\007");
            keyMap.bind(new Reference("newline"), "\033[13;2u");
        }

        CompletableFuture<String> read() {
            var result = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(mode == 1
                            ? reader.readLineFramed(frameMode, () -> "footer", () -> List.of("history"))
                            : mode == 0 ? reader.readLine(() -> "> ", () -> {}) : reader.readLine("> "));
                } catch (Throwable e) { result.completeExceptionally(e); }
            });
            return result;
        }

        void send(String text) throws Exception {
            keys.write(text.getBytes(StandardCharsets.UTF_8)); keys.flush();
        }
        void sendAndAwait(String text) throws Exception {
            send(text + "\007");
            assertTrue(marks.tryAcquire(5, TimeUnit.SECONDS), "editor checkpoint not reached");
        }
        @Override public void close() throws Exception {
            if (screen != null) screen.close();
            var status = Status.getStatus(terminal, false);
            if (status != null) status.close();
            terminal.close(); keys.close(); input.close();
        }
    }

    @Test
    void resizeContendingWithEditorWidgetCompletes() throws Exception {
        Path log = temp.resolve("probe.log");
        Process child = start(log);
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "TIMEOUT: isolated resize probe");
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("WIDGET_HELD; SIGNAL_WAITING_FOR_READER_LOCK"));
            assertTrue(read(log).contains("PROBE_OK abcdef"));
        } finally {
            stop(child);
        }
    }

    @Test
    void hungProbeIsTerminatedWithoutStrandingTestRunner() throws Exception {
        Path log = temp.resolve("hang.log");
        Process child = start(log, "hang");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!read(log).contains("PROBE_READY") && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(read(log).contains("PROBE_READY"), () -> read(log));
            assertFalse(child.waitFor(100, TimeUnit.MILLISECONDS));
        } finally {
            stop(child);
        }
        assertFalse(child.isAlive());
    }

    private Process start(Path log, String... args) throws Exception {
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:ActiveProcessorCount=2", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ResizeDeadlockProbe.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void stop(Process child) throws Exception {
        if (child.isAlive()) child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS), "probe process did not exit");
    }

    private static String read(Path log) {
        try { return Files.exists(log) ? Files.readString(log) : ""; }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
