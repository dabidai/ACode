package com.acode.ui;

import com.acode.command.CommandRegistry;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResizeAwareLineReaderTest {

    @Test
    void longMultilineInputSurvivesFrameViewportAndResize() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .type("xterm-256color")
                     .streams(input, output)
                     .size(new Size(80, 24))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLineFramed(() -> "default", () -> "footer", () -> {}, java.util.List::of));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            Thread.sleep(200);
            StringBuilder expected = new StringBuilder("x".repeat(150));
            StringBuilder events = new StringBuilder(expected);
            for (int i = 0; i < 12; i++) {
                expected.append('\n').append("line-").append(i);
                events.append("\033[13;2u").append("line-").append(i);
            }
            keys.write(events.toString().getBytes(StandardCharsets.UTF_8));
            keys.flush();
            terminal.setSize(new Size(60, 20));
            terminal.raise(Terminal.Signal.WINCH);
            keys.write('\r');
            keys.flush();
            assertEquals(expected.toString(), result.get(3, TimeUnit.SECONDS));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\033[3J"));
        }
    }

    @Test
    void wheelScrollsConversationWhileKeepingTheInputBuffer() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .type("xterm-256color")
                     .streams(input, output)
                     .size(new Size(80, 24))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            java.util.List<String> history = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(i -> "line-" + i).toList();
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLineFramed(() -> "default", () -> "footer", () -> {}, () -> history));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            Thread.sleep(200);
            keys.write("abc\033[<64;1;1Mdef\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            assertEquals("abcdef", result.get(3, TimeUnit.SECONDS));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("line-35"));
        }
    }

    @Test
    void statusOwnsWholeFrameAndKeepsMultilineInputAcrossResize() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .type("xterm-256color")
                     .streams(input, output)
                     .size(new Size(80, 24))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            AtomicInteger replays = new AtomicInteger();
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLineFramed(() -> "default", () -> "model · ctx 1% · project",
                            replays::incrementAndGet, () -> java.util.List.of("older", "newer")));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            Thread.sleep(200);
            keys.write("abc\033[13;2u".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            for (int i = 0; i < 30 && !output.toString(StandardCharsets.UTF_8).contains("abc"); i++) {
                Thread.sleep(20);
            }
            assertEquals(12, org.jline.utils.Status.getStatus(terminal, false).size(),
                    "输入换行时状态区高度应保持不变，编辑行从上方预留空间展开");
            terminal.setSize(new Size(55, 20));
            terminal.raise(Terminal.Signal.WINCH);
            keys.write("def\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            assertEquals("abc\ndef", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, replays.get());
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("[default]"));
            assertTrue(rendered.contains("project"));
            assertTrue(rendered.contains("\033[3J"));
        }
    }

    @Test
    void rebuildsPromptAndFooterWhileReadLineIsActive() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .streams(input, output)
                     .size(new Size(80, 24))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            CountDownLatch resized = new CountDownLatch(1);
            AtomicInteger resizeCalls = new AtomicInteger();
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLine(
                            () -> StatusBar.framedInputPrompt("w" + terminal.getWidth(), terminal.getWidth()), () -> {
                                resizeCalls.incrementAndGet();
                                resized.countDown();
                            }));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });

            Thread.sleep(200);
            terminal.setSize(new Size(60, 20));
            terminal.raise(Terminal.Signal.WINCH);
            assertTrue(resized.await(3, TimeUnit.SECONDS), "WINCH handler 应在活动读取期间触发 footer 回调");
            Thread.sleep(300);
            assertEquals(1, resizeCalls.get(), "一次 WINCH 只能触发一条重绘链，不得再有轮询线程二次重绘");

            keys.write("hello\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            assertEquals("hello", result.get(3, TimeUnit.SECONDS));
            assertTrue(pane.wasResizedDuringLastRead(), "主循环必须能识别本轮 resize 并丢弃旧 pin 距离");
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("[w60]"),
                    "重绘输出应包含按新宽度生成的 prompt");
            assertTrue(!rendered.contains("\033[6n"),
                    "活动 readLine 的 resize 修复不得发送 CPR，否则响应会与用户输入竞争");

            CompletableFuture<String> nextResult = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    nextResult.complete(pane.readLine(
                            () -> StatusBar.framedInputPrompt("w" + terminal.getWidth(), terminal.getWidth()), () -> { }));
                } catch (Throwable t) {
                    nextResult.completeExceptionally(t);
                }
            });
            Thread.sleep(200);
            keys.write("again\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            assertEquals("again", nextResult.get(3, TimeUnit.SECONDS));
            assertTrue(!pane.wasResizedDuringLastRead(),
                    "resize 标记只属于发生信号的那一轮，下一轮必须恢复正常 pin/unpin");
        }
    }

    @Test
    void preservesEditedTextAcrossResizeStorm() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .streams(input, output)
                     .size(new Size(100, 30))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            AtomicInteger resizeCalls = new AtomicInteger();
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLine(
                            () -> StatusBar.framedInputPrompt("w" + terminal.getWidth(), terminal.getWidth()),
                            resizeCalls::incrementAndGet));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });

            Thread.sleep(200);
            keys.write("abc".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            for (int i = 0; i < 20; i++) {
                terminal.setSize(i % 2 == 0 ? new Size(60, 20) : new Size(120, 35));
                terminal.raise(Terminal.Signal.WINCH);
            }
            keys.write("def\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();

            assertEquals("abcdef", result.get(3, TimeUnit.SECONDS));
            assertEquals(20, resizeCalls.get(), "连续 WINCH 不得产生轮询或第二条回调链");
            assertTrue(pane.wasResizedDuringLastRead());
            assertTrue(!output.toString(StandardCharsets.UTF_8).contains("\033[6n"),
                    "事件风暴期间同样不得发送 CPR");
        }
    }

    @Test
    void preservesMultilineBufferAcrossResize() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream keys = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             Terminal terminal = TerminalBuilder.builder()
                     .system(false)
                     .streams(input, output)
                     .size(new Size(80, 24))
                     .build()) {
            InputPane pane = new InputPane(terminal, InputPane.DEFAULT_PROMPT, new CommandRegistry());
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    result.complete(pane.readLine(
                            () -> StatusBar.framedInputPrompt("default", terminal.getWidth()), () -> { }));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });

            Thread.sleep(200);
            keys.write("abc\033[13;2u".getBytes(StandardCharsets.UTF_8));
            keys.flush();
            terminal.setSize(new Size(55, 20));
            terminal.raise(Terminal.Signal.WINCH);
            keys.write("def\r".getBytes(StandardCharsets.UTF_8));
            keys.flush();

            assertEquals("abc\ndef", result.get(3, TimeUnit.SECONDS));
            assertTrue(pane.wasResizedDuringLastRead());
        }
    }
}
