package com.acode.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void linesReturnsSnapshotNotLiveView() {
        // 流式输出时读取方与 append 并发，lines() 必须是稳定快照，否则按 size 预分配数组的遍历会越界
        OutputPane pane = new OutputPane();
        pane.append("a");
        List<String> snapshot = pane.lines();
        pane.appendLine("b");
        assertEquals(List.of("a"), snapshot);
        assertEquals(List.of("a", "b"), pane.lines());
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
}
