package com.acode.ui;

import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamPrinterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void toolUseCommitsPriorTextAndAppendsRunningCard() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onDelta("先看下文件");
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        assertEquals(2, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("先看下文件"), "文本应保留在卡片上方");
        String card = pane.lines().get(1);
        assertTrue(card.contains("ReadFile"));
        assertTrue(card.contains("运行中"));
    }

    @Test
    void toolUseWithNoPriorTextStartsWithCard() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onToolUse(new ToolUseBlock("id-1", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        assertEquals(1, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("Bash"));
    }

    @Test
    void updateToolCallsReplacesRunningCardsWithDoneState() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        printer.onToolUse(new ToolUseBlock("id-2", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        assertEquals(2, pane.lineCount());
        printer.updateToolCalls(List.of(ToolResult.success("文件内容"), ToolResult.failure("命令失败")));
        assertEquals(2, pane.lineCount(), "卡片行数应保持不变");
        assertTrue(pane.lines().get(0).contains("成功"));
        assertTrue(pane.lines().get(0).contains("文件内容"));
        assertTrue(pane.lines().get(1).contains("失败"));
        assertTrue(pane.lines().get(1).contains("命令失败"));
    }

    @Test
    void deltaAfterToolUseIsIgnoredToProtectCard() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        printer.onDelta("不应出现的文本");
        assertEquals(1, pane.lineCount(), "tool_use 后的文本增量应被忽略，不覆盖卡片");
    }

    @Test
    void completeKeepsToolCardsInHistory() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = new StreamPrinter(pane, () -> { });
        printer.onToolUse(new ToolUseBlock("id-1", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        printer.onComplete();
        assertEquals(1, pane.lineCount(), "卡片应作为历史保留");
    }
}
