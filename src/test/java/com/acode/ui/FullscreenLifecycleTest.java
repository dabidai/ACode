package com.acode.ui;

import com.acode.command.CommandRegistry;
import com.acode.config.AppConfig;
import com.acode.provider.ChatMessage;
import com.acode.session.SessionManager;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FullscreenLifecycleTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    static final class Fixture implements AutoCloseable {
        final PipedInputStream input = new PipedInputStream();
        final PipedOutputStream keys = new PipedOutputStream(input);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Terminal terminal = TerminalBuilder.builder().system(false).encoding(StandardCharsets.UTF_8).type("xterm-256color")
                .stdinEncoding(StandardCharsets.UTF_8).stdoutEncoding(StandardCharsets.UTF_8)
                .streams(input, bytes).size(new Size(80, 24)).build();
        final OutputPane output = new OutputPane();
        final AcodeTerminal tui = new AcodeTerminal(terminal);
        final ScreenRenderer screen = tui.openScreen(output);
        Fixture() throws Exception { screen.open(); screen.labels(() -> "default", () -> "footer"); }
        FakeTerminal view() { FakeTerminal t = new FakeTerminal(80, 24); t.write(bytes.toString(StandardCharsets.UTF_8)); return t; }
        void send(String text) throws Exception { keys.write(text.getBytes(StandardCharsets.UTF_8)); keys.flush(); }
        public void close() throws Exception { screen.close(); terminal.close(); keys.close(); input.close(); }
    }

    @Test void startupKeepsShortTranscriptAtTopAndSingleFrameAtBottom() throws Exception {
        try (var f = new Fixture()) {
            f.output.append("Welcome\nhelp\n"); f.screen.edit("", 0);
            FakeTerminal v = f.view();
            assertEquals("Welcome", v.line(0)); assertEquals("help", v.line(1));
            assertEquals("[default]", v.line(19)); assertEquals(">", v.line(21));
            assertEquals("footer", v.line(23));
            assertEquals(1, v.screenText().lines().filter(s -> s.equals("[default]")).count());
            assertEquals(List.of("Welcome", "help"), f.output.lines());
        }
    }

    @Test void resumeReplacementAndMenuCancelNeverLeaveChromeInTranscript() throws Exception {
        try (var f = new Fixture()) {
            f.output.append("old\nold reply"); f.screen.edit("", 0);
            List<String> before = f.view().screenText().lines().toList();
            f.screen.overlay(List.of("choose", "\033[7m> session\033[0m"));
            f.screen.overlay(List.of());
            assertEquals(before, f.view().screenText().lines().toList());
            for (int turn = 0; turn < 5; turn++) {
                RenderContext context = new RenderContext(new AppConfig());
                context.setLive(f.screen); context.setScreenWriter(f.terminal.writer());
                SessionManager manager = new SessionManager(new com.acode.session.SessionStore(temp),
                        new com.acode.conversation.Conversation("test", false, 4096, 2000));
                manager.attachUi(f.output, context, null);
                manager.renderLoaded("恢复", "synthetic", List.of(ChatMessage.of(ChatMessage.Role.ASSISTANT, "first\n\nthird")));
                f.screen.edit("", 0);
                FakeTerminal v = f.view();
                assertTrue(v.line(0).contains("synthetic")); assertEquals("first", v.line(1));
                assertEquals("", v.line(2)); assertEquals("third", v.line(3));
                assertFalse(v.screenText().contains("old"));
                assertEquals("[default]", v.line(19));
            }
        }
    }

    @Test void scrollAnchorSurvivesAppendsAndFrameStaysFixed() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 80; i++) f.output.appendLine("line-" + i);
            f.screen.edit("draft", 5); f.screen.page(-1);
            String first = f.view().line(0);
            for (int i = 0; i < 20; i++) f.output.appendLine("new-" + i);
            f.screen.refresh();
            assertEquals("有新内容", ScreenLayout.clip("有新内容", 79));
            assertEquals(first, f.view().line(0));
            assertTrue(f.view().line(19).contains("有新内容"), f.view().screenText());
            assertEquals("> draft", f.view().line(21));
            f.screen.bottom(); assertTrue(f.view().screenText().contains("new-19"));
        }
    }

    @Test void incompleteStreamTailIsVisibleWithoutNewline() throws Exception {
        try (var f = new Fixture()) {
            f.output.appendLine("question");
            var printer = new StreamPrinter(f.output, f.screen, f.terminal.writer(), false);
            printer.onDelta("first"); assertEquals("first", f.view().line(1));
            printer.onDelta(" second"); assertEquals("first second", f.view().line(1));
            printer.finishTurn(); assertEquals(List.of("question", "first second"), f.output.lines());
        }
    }

    @Test void editorResizeSubmitDoesNotWriteJLineFramesToPhysicalTerminal() throws Exception {
        try (var f = new Fixture()) {
            var pane = new InputPane(f.terminal, "> ", new CommandRegistry(), f.screen);
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> { try { result.complete(pane.readLine()); } catch (Throwable e) { result.completeExceptionally(e); } });
            f.send("abc\033[13;2udef");
            for (int i = 0; i < 100 && !f.view().screenText().contains("def"); i++) Thread.sleep(20);
            assertTrue(f.view().screenText().contains("def"));
            for (int i = 0; i < 20; i++) {
                f.terminal.setSize(new Size(i % 2 == 0 ? 40 : 80, i % 2 == 0 ? 12 : 24));
                f.terminal.raise(Terminal.Signal.WINCH);
            }
            f.send("\r"); assertEquals("abc\ndef", result.get(5, TimeUnit.SECONDS));
            f.screen.submitted(); f.screen.edit("next", 4);
            assertEquals("> next", f.view().line(21));
            assertEquals("[default]", f.view().line(19));
            assertFalse(f.bytes.toString(StandardCharsets.UTF_8).contains("\033[6n"));
        }
    }

    @Test void noChangeRefreshWritesNothingAndCloseRestoresPrimaryScreen() throws Exception {
        try (var f = new Fixture()) {
            f.screen.edit("", 0); int length = f.bytes.size(); f.screen.refresh();
            assertEquals(length, f.bytes.size());
            f.screen.close(); length = f.bytes.size(); f.screen.close();
            assertEquals(length, f.bytes.size());
            FakeTerminal v = new FakeTerminal(80, 24); v.write("shell prompt");
            v.write(f.bytes.toString(StandardCharsets.UTF_8));
            assertEquals("shell prompt", v.line(0));
        }
    }

    @Test void simulatorHandlesAbsoluteCursorScrollRegionAndSavedCursor() {
        FakeTerminal v = new FakeTerminal(10, 5);
        v.write("top\033[2;4r\033[2;1Hone\r\ntwo\r\nthree\r\nfour");
        assertEquals("top", v.line(0)); assertEquals("two", v.line(1)); assertEquals("four", v.line(3));
        v.write("\033[1;5H\033[s\033[5;1Hbottom\033[uX");
        assertEquals("top X", v.line(0)); assertEquals("bottom", v.line(4));
    }

    @Test void tinyWindowAndLongEditorStayInsideFrameThenRecover() throws Exception {
        try (var f = new Fixture()) {
            f.output.appendLine("history");
            String input = String.join("\n", java.util.Collections.nCopies(12, "draft"));
            f.screen.edit(input, input.length());
            assertEquals("[default]", f.view().line(12));
            assertEquals("history", f.view().line(0));
            f.terminal.setSize(new Size(10, 3)); f.screen.refresh();
            f.terminal.setSize(new Size(80, 24)); f.screen.refresh();
            assertEquals("[default]", f.view().line(12));
            assertEquals("footer", f.view().line(23));
        }
    }
    @Test void unicodeClustersAndEmbeddedNewlinesRespectCellWidth() {
        assertEquals(2, ScreenLayout.cells("👩‍💻"));
        assertEquals(1, ScreenLayout.cells("é"));
        assertEquals(List.of("a", "b"), ScreenLayout.wrap("a\nb", 20).stream().map(ScreenLayout.TextRow::text).toList());
        assertEquals(List.of("中文", "abc"), ScreenLayout.wrap("中文abc", 4).stream().map(ScreenLayout.TextRow::text).toList());
        assertFalse(ScreenLayout.editor("hello\033[2J", 10, 79).rows().getFirst().contains("\033"));
    }
    @Test void plainTextFallbackEmitsNoControlSequences() {
        StringWriter writer = new StringWriter();
        var renderer = new PlainTextRenderer();
        renderer.appendCommitted(writer, "\033[31m正文\033[0m\n\n段落");
        renderer.redraw(writer, List.of("menu")); renderer.clear(writer); renderer.clearScreen(writer);
        assertEquals("正文\r\n\r\n段落\r\nmenu\r\n", writer.toString());
    }
    @Test void viewportAnchorSurvivesReflowAndRetentionLimit() {
        var pane = new OutputPane(25);
        for (int i = 0; i < 25; i++) pane.appendLine("line-" + i + " abcdefghijk");
        var view = new TranscriptViewport();
        view.visible(pane.snapshot(), 30, 10); view.scroll(-4);
        String anchor = view.visible(pane.snapshot(), 30, 10).getFirst();
        assertTrue(anchor.startsWith(view.visible(pane.snapshot(), 10, 10).getFirst()));
        for (int i = 0; i < 30; i++) pane.appendLine("new-" + i);
        view.visible(pane.snapshot(), 30, 10); view.scroll(-100);
        assertEquals("更早内容未保留", view.visible(pane.snapshot(), 30, 10).getFirst());
    }

    @Test void bracketedPasteAndTabCompletionUseSharedEditorWithoutLeakingFrames() throws Exception {
        try (var f = new Fixture()) {
            CommandRegistry registry = new CommandRegistry();
            com.acode.command.BuiltinCommands.registerAll(registry);
            var pane = new InputPane(f.terminal, "> ", registry, f.screen);
            CompletableFuture<String> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> { try { result.complete(pane.readLine()); } catch (Throwable e) { result.completeExceptionally(e); } });
            f.send("\033[200~first\nsecond\033[201~\r");
            assertEquals("first\nsecond", result.get(5, TimeUnit.SECONDS));
            CompletableFuture<String> command = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> { try { command.complete(pane.readLine()); } catch (Throwable e) { command.completeExceptionally(e); } });
            f.send("/cop\t\r");
            assertEquals("/copy ", command.get(5, TimeUnit.SECONDS));
            assertTrue(f.bytes.toString(StandardCharsets.UTF_8).contains("\033[?2004h"));
        }
    }
    @Test void controlCAndEmptyControlDLeaveEditorNormally() throws Exception {
        for (String key : List.of("\003", "\004")) {
            try (var f = new Fixture()) {
                var pane = new InputPane(f.terminal, "> ", new CommandRegistry(), f.screen);
                CompletableFuture<Throwable> result = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try { pane.readLine(); result.complete(null); }
                    catch (Throwable e) { result.complete(e); }
                });
                // Wait for readLine to install its signal handler before sending interrupt/EOF.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!f.bytes.toString(StandardCharsets.UTF_8).contains("\033[?25h")) {
                    assertTrue(System.nanoTime() < deadline, "Editor did not become ready");
                    Thread.sleep(5);
                }
                f.send(key);
                Throwable failure = result.get(5, TimeUnit.SECONDS);
                assertTrue(failure instanceof org.jline.reader.UserInterruptException
                        || failure instanceof org.jline.reader.EndOfFileException);
                f.screen.close();
                assertTrue(f.bytes.toString(StandardCharsets.UTF_8).endsWith("\033[?1049l"));
            }
        }
    }
    @Test void tallMenuKeepsSelectionAboveInputAndNoticeVisible() throws Exception {
        try (var f = new Fixture()) {
            List<String> menu = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) menu.add((i == 45 ? "\033[7m> " : "  ") + "session-" + i);
            f.screen.overlay(menu);
            assertTrue(f.view().screenText().contains("> session-45"));
            assertEquals("[default]", f.view().line(19));
            f.screen.overlay(List.of("question\nsecond line", "\033[7m> allow"));
            assertEquals("question", f.view().line(0));
            assertEquals("second line", f.view().line(1));
            assertEquals("> allow", f.view().line(2));
        }
    }

    @Test void completedToolsKeepTheirHeadingAndClearRunningActivity() throws Exception {
        try (var f = new Fixture()) {
            var printer = new StreamPrinter(f.output, f.screen, f.terminal.writer(), false);
            printer.onToolUse(new com.acode.provider.ToolUseBlock("one", "read_file", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
            printer.updateToolCalls(List.of(com.acode.tool.ToolResult.success("result text")), List.of(10L));
            printer.updateToolCalls(List.of(com.acode.tool.ToolResult.success("result text")), List.of(10L));
            printer.finishTurn();
            assertEquals(1, f.output.lines().stream().filter(line -> line.contains("read_file")).count());
            assertTrue(f.view().line(0).contains("read_file"));
            assertEquals("[default]", f.view().line(19));
            assertTrue(f.view().screenText().contains("result text"));
        }
    }
}
