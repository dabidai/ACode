package com.acode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Reference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs in a disposable JVM: a regression must not strand the test runner. */
public final class ResizeDeadlockProbe {
    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("hang")) {
                Thread.ofVirtual().name("intentional-hang-worker").start(() -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                System.out.println("PROBE_READY");
                new CountDownLatch(1).await();
            }
            run();
            System.out.println("PROBE_OK abcdef");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            // Do not close a terminal whose signal/editor threads may be deadlocked.
            System.exit(2);
        }
    }

    private static void run() throws Exception {
        var input = new PipedInputStream();
        var keys = new PipedOutputStream(input);
        var output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm-256color")
                .streams(input, output).size(new Size(80, 24)).build();
        var reader = new ResizeAwareLineReader(terminal, "deadlock-probe");
        var widgetEntered = new CountDownLatch(1);
        var releaseWidget = new CountDownLatch(1);
        reader.getWidgets().put("hold-editor", () -> {
            // Widgets run inside JLine's real input lock.
            widgetEntered.countDown();
            try {
                if (!releaseWidget.await(8, TimeUnit.SECONDS)) throw new AssertionError("widget not released");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return true;
        });
        reader.getKeyMaps().get(org.jline.reader.LineReader.MAIN)
                .bind(new Reference("hold-editor"), KeyMap.ctrl('G'));
        var result = new CompletableFuture<String>();
        Thread editor = Thread.ofVirtual().name("probe-editor").start(() -> {
            try { result.complete(reader.readLine("> ")); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        keys.write("abc\007".getBytes(StandardCharsets.UTF_8));
        keys.flush();
        if (!widgetEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("widget not entered");
        terminal.setSize(new Size(60, 20));
        var signalResult = new CompletableFuture<Void>();
        Thread signal = Thread.ofPlatform().daemon().name("probe-signal").start(() -> {
            try { terminal.raise(Terminal.Signal.WINCH); signalResult.complete(null); }
            catch (Throwable e) { signalResult.completeExceptionally(e); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!waitingForReaderLock(signal)) {
            if (System.nanoTime() > deadline || signalResult.isDone())
                throw new AssertionError("signal did not contend on reader lock");
            Thread.yield();
        }
        System.out.println("WIDGET_HELD; SIGNAL_WAITING_FOR_READER_LOCK");
        releaseWidget.countDown();
        keys.write("def\r".getBytes(StandardCharsets.UTF_8));
        keys.flush();
        try {
            if (!"abcdef".equals(result.get(8, TimeUnit.SECONDS))) throw new AssertionError("lost input");
            signalResult.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            for (Thread thread : new Thread[]{editor, signal}) {
                System.err.println(thread.getName() + " " + thread.getState());
                for (StackTraceElement frame : thread.getStackTrace()) System.err.println("  at " + frame);
            }
            throw e;
        }
        terminal.close();
        keys.close();
        input.close();
    }

    private static boolean waitingForReaderLock(Thread signal) {
        if (signal.getState() != Thread.State.WAITING) return false;
        boolean signalFrame = false, lockFrame = false;
        for (StackTraceElement frame : signal.getStackTrace()) {
            signalFrame |= frame.getMethodName().equals("handleSignal");
            lockFrame |= frame.getClassName().equals("java.util.concurrent.locks.ReentrantLock");
        }
        return signalFrame && lockFrame;
    }
}
