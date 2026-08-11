package com.acode.ui;

import com.acode.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamPrinterTest {

    @Test
    void deltaRendersIntoOutputPane() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("hi");
        assertEquals("hi", pane.lines().get(0));
    }

    @Test
    void deltaAccumulatesAndReplacesTail() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("hel");
        assertEquals(1, pane.lineCount());
        printer.onDelta("lo");
        assertEquals(1, pane.lineCount());
        assertEquals("hello", pane.lines().get(0));
    }

    @Test
    void multilineDeltaUpdatesWholeBlock() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("a\nb");
        assertEquals(2, pane.lineCount());
        printer.onDelta("c");
        assertEquals(2, pane.lineCount());
        assertEquals("a", pane.lines().get(0));
        assertEquals("bc", pane.lines().get(1));
    }

    @Test
    void completeKeepsBlockAsHistory() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("done");
        printer.onComplete();
        assertEquals(1, pane.lineCount());
        printer.onDelta("next");
        assertEquals(2, pane.lineCount());
        assertEquals("done", pane.lines().get(0));
        assertEquals("next", pane.lines().get(1));
    }

    @Test
    void errorRemovesPartialAndShowsMessage() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("partial");
        printer.onError(new ProviderException("网络失败"));
        assertEquals(1, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("网络失败"));
        assertTrue(pane.lines().get(0).contains("错误"));
    }

    @Test
    void errorWithoutMessageUsesTypeName() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onError(new ProviderException(null));
        assertEquals(1, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("ProviderException"));
    }

    @Test
    void redrawCalledOnDeltaAndError() {
        OutputPane pane = new OutputPane();
        AtomicInteger calls = new AtomicInteger();
        StreamPrinter printer = new StreamPrinter(pane, calls::incrementAndGet);
        printer.onDelta("a");
        printer.onDelta("b");
        printer.onError(new ProviderException("x"));
        assertEquals(3, calls.get());
    }

    @Test
    void coloredDeltaRendersWithAnsi() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("`code`");
        assertTrue(pane.lines().get(0).contains(MarkdownRenderer.STYLE_INLINE_CODE));
    }
}
