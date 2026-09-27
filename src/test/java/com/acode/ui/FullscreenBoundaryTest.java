package com.acode.ui;

import com.acode.provider.ProviderException;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FullscreenBoundaryTest {
    private static String since(FullscreenLifecycleTest.Fixture f, int from) {
        byte[] bytes = f.bytes.toByteArray();
        return new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
    }

    @Test void everyIntermediateSizeHasBoundedCoordinatesAndVisibleInput() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            f.output.append("first\nsecond\nthird");
            f.screen.edit("draft", 5);
            for (int width : new int[]{1, 2, 19, 20, 40, 120}) {
                for (int height : new int[]{1, 2, 5, 6, 12, 40}) {
                    int from = f.bytes.size();
                    f.terminal.setSize(new Size(width, height));
                    f.terminal.raise(Terminal.Signal.WINCH);
                    String frame = since(f, from);
                    var cursor = Pattern.compile("\\033\\[(\\d+);(\\d+)H").matcher(frame);
                    int moves = 0;
                    while (cursor.find()) {
                        int row = Integer.parseInt(cursor.group(1)), col = Integer.parseInt(cursor.group(2));
                        assertTrue(row >= 1 && row <= height, width + "x" + height + " row=" + row);
                        assertTrue(col >= 1 && col <= width, width + "x" + height + " col=" + col);
                        moves++;
                    }
                    assertTrue(moves > 0);
                    FakeTerminal view = new FakeTerminal(width, height);
                    view.write(frame);
                    assertFalse(view.line(height - 1).isEmpty(), width + "x" + height + " bottom is empty");
                    if (width >= 20 && height >= 6) {
                        assertEquals("footer", view.line(height - 1));
                        assertEquals("[default]", view.line(height - 5));
                        assertEquals("> draft", view.line(height - 3));
                    }
                }
            }
        }
    }

    @Test void busyNavigationConsumesWholeSequencesAndLeavesCancelReachable() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            // Feed bytes to the poller, without ExternalTerminal converting Ctrl+C to a signal.
            f.terminal.enterRawMode();
            var attributes = f.terminal.getAttributes();
            attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG, false);
            f.terminal.setAttributes(attributes);
            for (int i = 0; i < 80; i++) f.output.appendLine("line-" + i);
            f.screen.submitted();
            assertEquals("line-61", f.view().line(0));
            for (String sequence : List.of("\033[5~", "\033[6~", "\033[<64;2;3M", "\033[<65;2;3M", "\033[1;5F", "\033[4;5~")) {
                f.send(sequence + "\003");
                assertFalse(f.screen.pollNavigation());
                assertTrue(f.screen.pollNavigation(), "Cancel must follow " + sequence);
            }
            assertEquals("line-61", f.view().line(0));
            f.send("\033[5~"); f.screen.pollNavigation();
            assertEquals("line-42", f.view().line(0));
            f.send("\033[<64;2;3M"); f.screen.pollNavigation();
            assertEquals("line-39", f.view().line(0));
        }
    }

    @Test void ignoredTypingDuringBusyOutputCannotBlockControlC() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            // Feed bytes to the poller, without ExternalTerminal converting Ctrl+C to a signal.
            f.terminal.enterRawMode();
            var attributes = f.terminal.getAttributes();
            attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG, false);
            f.terminal.setAttributes(attributes);
            f.send("x\003");
            assertFalse(f.screen.pollNavigation());
            assertTrue(f.screen.pollNavigation(), "An unsupported busy key must not permanently mask cancellation");
        }
    }

    @Test void sameSizeSignalAndClosedScreenNeverWriteAnotherFrame() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            AtomicInteger previousCalls = new AtomicInteger();
            f.screen.close();
            f.terminal.handle(Terminal.Signal.WINCH, signal -> previousCalls.incrementAndGet());
            try (var screen = new ScreenRenderer(f.terminal, f.output)) {
                screen.open(); screen.edit("draft", 5);
                int before = f.bytes.size();
                f.terminal.raise(Terminal.Signal.WINCH);
                assertEquals(before, f.bytes.size());
                screen.close();
                before = f.bytes.size();
                f.terminal.raise(Terminal.Signal.WINCH);
                screen.refresh(); screen.edit("late", 4); screen.open();
                assertEquals(1, previousCalls.get());
                assertEquals(before, f.bytes.size());
            }
        }
    }

    @Test void nestedBatchUpdatesExposeOnlyTheFinalTranscript() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            f.screen.edit("", 0);
            int before = f.bytes.size();
            f.screen.beginUpdate(); f.screen.beginUpdate();
            f.output.clear(); f.output.appendLine("first"); f.screen.refresh();
            f.screen.endUpdate();
            assertEquals(before, f.bytes.size());
            f.output.appendLine("last"); f.screen.endUpdate();
            assertEquals("first", f.view().line(0)); assertEquals("last", f.view().line(1));
            assertEquals(1, since(f, before).split("\\033\\[\\?2026h", -1).length - 1);
        }
    }

    @Test void failedPartialReplyIsRemovedAndNextReplyIsNotLost() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            f.output.append("question\nold answer");
            StreamPrinter printer = new StreamPrinter(f.output, f.screen, f.terminal.writer(), false);
            printer.onDelta("partial\nreply");
            printer.onError(new ProviderException("offline"));
            assertEquals(List.of("question", "old answer", "（错误：offline）"), f.output.lines());
            assertFalse(f.view().screenText().contains("partial"));
            printer.onDelta("recovered"); printer.finishTurn();
            assertEquals("recovered", f.output.lines().getLast());
            assertTrue(f.view().screenText().contains("old answer"));
        }
    }

    @Test void replacingAStreamTailAtRetentionLimitDoesNotDuplicateOldChunks() throws Exception {
        try (var f = new FullscreenLifecycleTest.Fixture()) {
            for (int i = 0; i < 1999; i++) f.output.appendLine("old-" + i);
            StreamPrinter printer = new StreamPrinter(f.output, f.screen, f.terminal.writer(), false);
            printer.onDelta("one\ntwo"); printer.onDelta("\nthree"); printer.finishTurn();
            List<String> lines = f.output.lines();
            assertEquals(2000, lines.size());
            assertEquals(List.of("one", "two", "three"), lines.subList(lines.size() - 3, lines.size()));
            assertEquals(1, lines.stream().filter("one"::equals).count());
            assertEquals("old-2", lines.getFirst());
        }
    }

    @Test void styleSurvivesWrappingAndIncompleteTailUpdatesMarkUnseen() {
        OutputPane output = new OutputPane();
        for (int i = 0; i < 8; i++) output.appendLine("row-" + i);
        output.appendLine("\033[31mabcdefghij\033[0m");
        TranscriptViewport viewport = new TranscriptViewport();
        var tail = viewport.visible(output.snapshot(), 5, 3);
        assertEquals(List.of("row-7", "abcde", "fghij"), tail.stream().map(ScreenLayout::plain).toList());
        assertTrue(tail.getLast().contains("\033[31m"));
        viewport.scroll(-3); String anchor = viewport.visible(output.snapshot(), 5, 3).getFirst();
        output.removeLast(1); output.appendLine("abcdefghijk");
        assertEquals(anchor, viewport.visible(output.snapshot(), 5, 3).getFirst());
        assertTrue(viewport.unseen()); viewport.bottom(); viewport.visible(output.snapshot(), 5, 3);
        assertFalse(viewport.unseen());
    }
}
