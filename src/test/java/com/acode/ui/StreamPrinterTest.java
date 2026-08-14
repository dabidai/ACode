package com.acode.ui;

import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamPrinterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static StreamPrinter printer(OutputPane pane) {
        return new StreamPrinter(pane, new LiveRegionRenderer(80, 24), new StringWriter(), false);
    }

    @Test
    void deltaRendersIntoOutputPane() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onDelta("hi");
        assertEquals("hi", pane.lines().get(0));
    }

    @Test
    void deltaAccumulatesAndReplacesTail() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onDelta("hel");
        assertEquals(1, pane.lineCount());
        printer.onDelta("lo");
        assertEquals(1, pane.lineCount());
        assertEquals("hello", pane.lines().get(0));
    }

    @Test
    void multilineDeltaUpdatesWholeBlock() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
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
        StreamPrinter printer = printer(pane);
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
        StreamPrinter printer = printer(pane);
        printer.onDelta("partial");
        printer.onError(new ProviderException("网络失败"));
        assertEquals(1, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("网络失败"));
        assertTrue(pane.lines().get(0).contains("错误"));
    }

    @Test
    void errorWithoutMessageUsesTypeName() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onError(new ProviderException(null));
        assertEquals(1, pane.lineCount());
        assertTrue(pane.lines().get(0).contains("ProviderException"));
    }

    @Test
    void writeHappensOnlyOnCompletedLine() {
        OutputPane pane = new OutputPane();
        CountingRenderer live = new CountingRenderer();
        StreamPrinter printer = new StreamPrinter(pane, live, new StringWriter(), false);
        printer.onDelta("a\n");   // 完整行 → 1 次写屏
        printer.onDelta("b");     // 未完成行不写屏
        printer.onError(new ProviderException("x")); // 错误行 → 共 2 次
        assertEquals(2, live.appends.get());
    }

    @Test
    void coloredDeltaRendersWithAnsi() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onDelta("`code`");
        assertTrue(pane.lines().get(0).contains(MarkdownRenderer.STYLE_INLINE_CODE));
    }

    @Test
    void blankLinesPreservedInScreenWrite() {
        OutputPane pane = new OutputPane();
        StringWriter sw = new StringWriter();
        StreamPrinter printer = new StreamPrinter(pane, new LiveRegionRenderer(80, 24), sw, false);
        printer.onDelta("a\n\nb\n");
        assertTrue(sw.toString().contains("a\r\n\r\nb\r\n"), "空行应保留在写屏输出：[" + sw + "]");
    }

    @Test
    void toolUseKeepsPriorTextInModelAndRunningCardInLiveRegion() {
        OutputPane pane = new OutputPane();
        StringWriter sw = new StringWriter();
        StreamPrinter printer = new StreamPrinter(pane, new LiveRegionRenderer(80, 24), sw, false);
        printer.onDelta("先看下文件");
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        assertEquals(1, pane.lineCount(), "运行中卡片不写入内容模型，模型只保留已提交文本");
        assertTrue(pane.lines().get(0).contains("先看下文件"), "文本应保留在模型中");
        assertTrue(sw.toString().contains("ReadFile"), "运行中卡片应追加写屏");
        assertTrue(sw.toString().contains("调用工具"));
    }

    @Test
    void toolUseWithNoPriorTextKeepsModelEmpty() {
        OutputPane pane = new OutputPane();
        StringWriter sw = new StringWriter();
        StreamPrinter printer = new StreamPrinter(pane, new LiveRegionRenderer(80, 24), sw, false);
        printer.onToolUse(new ToolUseBlock("id-1", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        assertEquals(0, pane.lineCount(), "无文本且运行中卡片不进模型");
        assertTrue(sw.toString().contains("Bash"));
    }

    @Test
    void updateToolCallsCommitsDoneCardsToModel() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        printer.onToolUse(new ToolUseBlock("id-2", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        assertEquals(0, pane.lineCount(), "运行中卡片不占模型行");
        printer.updateToolCalls(List.of(ToolResult.success("文件内容"), ToolResult.failure("命令失败")));
        assertEquals(2, pane.lineCount(), "终态卡片写入内容模型");
        assertTrue(pane.lines().get(0).contains("成功"));
        assertTrue(pane.lines().get(0).contains("文件内容"));
        assertTrue(pane.lines().get(1).contains("失败"));
        assertTrue(pane.lines().get(1).contains("命令失败"));
    }

    @Test
    void deltaAfterToolUseIsIgnoredToProtectCard() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onToolUse(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        printer.onDelta("不应出现的文本");
        assertEquals(0, pane.lineCount(), "tool_use 后的文本增量应被忽略，不覆盖卡片");
    }

    @Test
    void completeDoesNotCommitRunningCard() {
        OutputPane pane = new OutputPane();
        StreamPrinter printer = printer(pane);
        printer.onToolUse(new ToolUseBlock("id-1", "Bash",
                JSON.createObjectNode().put("command", "echo hi")));
        printer.onComplete();
        assertEquals(0, pane.lineCount(), "未 updateToolCalls 的运行中卡片不进入内容模型");
    }

    /** 计数追加写屏次数的假渲染器（追加式路径）。 */
    static class CountingRenderer extends LiveRegionRenderer {
        final AtomicInteger appends = new AtomicInteger();

        CountingRenderer() {
            super(80, 24);
        }

        @Override
        public void appendCommitted(Writer out, String text) {
            appends.incrementAndGet();
            super.appendCommitted(out, text);
        }
    }
}
