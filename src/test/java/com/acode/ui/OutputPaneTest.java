package com.acode.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputPaneTest {

    @Test
    void appendSplitsOnNewlines() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb\nc");
        assertEquals(3, pane.lineCount());
        assertEquals(List.of("a", "b", "c"), pane.lines());
    }

    @Test
    void appendStripsCarriageReturns() {
        OutputPane pane = new OutputPane();
        pane.append("a\r\nb");
        assertEquals(List.of("a", "b"), pane.lines());
    }

    @Test
    void appendIgnoresTrailingNewline() {
        OutputPane pane = new OutputPane();
        pane.append("a\n");
        assertEquals(1, pane.lineCount());
        assertEquals("a", pane.lines().get(0));
    }

    @Test
    void appendPreservesBlankMiddleLines() {
        OutputPane pane = new OutputPane();
        pane.append("a\n\nb");
        assertEquals(3, pane.lineCount());
        assertEquals(List.of("a", "", "b"), pane.lines());
    }

    @Test
    void appendBlankTextDoesNothing() {
        OutputPane pane = new OutputPane();
        pane.append("");
        pane.append(null);
        assertEquals(0, pane.lineCount());
    }

    @Test
    void appendLineAddsSingleLine() {
        OutputPane pane = new OutputPane();
        pane.appendLine("x");
        assertEquals(1, pane.lineCount());
        assertEquals("x", pane.lines().get(0));
    }

    @Test
    void clearEmptiesLines() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb");
        pane.clear();
        assertEquals(0, pane.lineCount());
    }

    @Test
    void visibleLinesReturnsTailWhenMoreLinesThanHeight() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
    }

    @Test
    void visibleLinesReturnsAllWhenFewerLinesThanHeight() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb\nc");
        assertEquals(List.of("a", "b", "c"), pane.visibleLines(10));
    }

    @Test
    void visibleLinesWithNonPositiveHeightIsEmpty() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb");
        assertTrue(pane.visibleLines(0).isEmpty());
        assertTrue(pane.visibleLines(-1).isEmpty());
    }

    @Test
    void visibleLinesOfEmptyPaneIsEmpty() {
        assertEquals(List.of(), new OutputPane().visibleLines(5));
    }

    @Test
    void dropsOldestWhenExceedingCap() {
        OutputPane pane = new OutputPane(5);
        for (int i = 1; i <= 8; i++) {
            pane.appendLine("line" + i);
        }
        assertEquals(5, pane.lineCount());
        assertEquals(List.of("line4", "line5", "line6", "line7", "line8"), pane.lines());
    }

    @Test
    void removeLastRemovesFromEnd() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb\nc");
        pane.removeLast(2);
        assertEquals(List.of("a"), pane.lines());
    }

    @Test
    void removeLastMoreThanPresentClearsAll() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb");
        pane.removeLast(5);
        assertEquals(0, pane.lineCount());
    }

    @Test
    void removeLastNonPositiveDoesNothing() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb");
        pane.removeLast(0);
        pane.removeLast(-1);
        assertEquals(List.of("a", "b"), pane.lines());
    }

    @Test
    void scrollUpShowsOlderLines() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
        pane.scrollUp(2);
        assertEquals(List.of("line5", "line6", "line7", "line8"), pane.visibleLines(4));
        pane.scrollUp(4);
        assertEquals(List.of("line1", "line2", "line3", "line4"), pane.visibleLines(4));
    }

    @Test
    void scrollUpClampsAtTop() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        pane.scrollUp(100);
        assertEquals(List.of("line1", "line2", "line3", "line4"), pane.visibleLines(4));
    }

    @Test
    void scrollDownReturnsTowardsBottom() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        pane.scrollUp(6);
        assertEquals(List.of("line1", "line2", "line3", "line4"), pane.visibleLines(4));
        pane.scrollDown(2);
        assertEquals(List.of("line3", "line4", "line5", "line6"), pane.visibleLines(4));
        pane.scrollDown(100);
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
    }

    @Test
    void scrollByMovesBothDirections() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        pane.scrollBy(4);
        assertEquals(List.of("line3", "line4", "line5", "line6"), pane.visibleLines(4));
        pane.scrollBy(-2);
        assertEquals(List.of("line5", "line6", "line7", "line8"), pane.visibleLines(4));
        pane.scrollBy(-100);
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
        pane.scrollBy(0);
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
    }

    @Test
    void resetScrollReturnsToBottom() {
        OutputPane pane = new OutputPane();
        for (int i = 1; i <= 10; i++) {
            pane.appendLine("line" + i);
        }
        pane.scrollUp(4);
        pane.resetScroll();
        assertEquals(List.of("line7", "line8", "line9", "line10"), pane.visibleLines(4));
    }

    @Test
    void scrollUpDoesNothingWhenContentFits() {
        OutputPane pane = new OutputPane();
        pane.append("a\nb\nc");
        pane.scrollUp(2);
        assertEquals(List.of("a", "b", "c"), pane.visibleLines(10));
    }
}
