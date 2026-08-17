package com.acode.ui;

import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallDisplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void summarizeParamsBuildsCompactText() {
        JsonNode input = JSON.createObjectNode()
                .put("file_path", "a.txt")
                .put("limit", 10);
        assertEquals("file_path=\"a.txt\" limit=10", ToolCallDisplay.summarizeParams(input));
    }

    @Test
    void summarizeParamsTruncatesLongValue() {
        JsonNode input = JSON.createObjectNode().put("command", "x".repeat(200));
        String summary = ToolCallDisplay.summarizeParams(input);
        assertTrue(summary.contains("…"), "长值应被截断");
        assertTrue(summary.length() < 60, "截断后应明显短于原值");
    }

    @Test
    void summarizeParamsEmptyForEmptyObject() {
        assertEquals("", ToolCallDisplay.summarizeParams(JSON.createObjectNode()));
    }

    @Test
    void runningCardShowsBulletAndToolName() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendRunning();
        assertEquals(1, card.lineCount());
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("●"), "运行行应以 ● 开头：" + line);
        assertTrue(line.contains("ReadFile"));
        assertFalse(line.contains("调用工具"), "不再用「调用工具」字样");
        assertTrue(line.contains(ToolCallDisplay.STYLE_NAME));
    }

    @Test
    void replacingLinesResetsScreenAppended() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"a.txt\"");
        card.appendRunning();
        card.markAppended(1);
        assertEquals(1, card.screenAppended());
        card.appendRunning();
        assertEquals(0, card.screenAppended(), "替换渲染行后未写屏计数应归零");
        card.appendDone(ToolResult.success("ok"), 0);
        assertEquals(0, card.screenAppended(), "终态块替换后未写屏计数应归零");
    }

    @Test
    void doneCardFirstLineColoredForSuccess() {
        ToolCallDisplay card = new ToolCallDisplay("Bash", "command=\"echo hi\"");
        List<String> lines = card.appendDone(ToolResult.success("hi\n"), 0);
        String first = lines.get(0);
        assertTrue(first.contains("  ⎿  "), "首行为 ⎿ 前缀：" + first);
        assertTrue(first.contains(ToolCallDisplay.STYLE_OK));
        assertTrue(first.contains("hi"));
        assertFalse(first.contains("成功"), "状态以颜色区分，不应有显式成功字");
    }

    @Test
    void doneCardFirstLineColoredForFailure() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"nope.txt\"");
        List<String> lines = card.appendDone(ToolResult.failure("文件不存在"), 0);
        String first = lines.get(0);
        assertTrue(first.contains("  ⎿  "));
        assertTrue(first.contains(ToolCallDisplay.STYLE_ERR));
        assertTrue(first.contains("文件不存在"));
        assertFalse(first.contains("失败"), "状态以颜色区分，不应有显式失败字");
    }

    @Test
    void doneCardMultiLineOutputIndented() {
        ToolCallDisplay card = new ToolCallDisplay("Grep", "pattern=\"x\"");
        List<String> lines = card.appendDone(ToolResult.success("line1\nline2\nline3"), 0);
        assertEquals(4, lines.size(), "三行内容 + 耗时脚注");
        assertTrue(lines.get(0).contains("line1"));
        assertTrue(lines.get(0).contains(ToolCallDisplay.STYLE_OK));
        assertTrue(lines.get(1).startsWith("     "), "后续行对齐缩进：" + lines.get(1));
        assertTrue(lines.get(1).contains("line2"));
        assertTrue(lines.get(2).startsWith("     "));
        assertTrue(lines.get(2).contains("line3"));
        assertFalse(lines.get(1).contains(ToolCallDisplay.STYLE_OK), "后续行不带成败色");
    }

    @Test
    void doneCardPreservesMiddleBlankLinesButDropsTrailing() {
        ToolCallDisplay card = new ToolCallDisplay("Bash", "command=\"ls\"");
        List<String> lines = card.appendDone(ToolResult.success("a\n\nb\n"), 0);
        assertEquals(4, lines.size(), "中间空行保留，末尾换行去除 + 脚注");
        assertTrue(lines.get(1).equals("     "), "中间空行应保留（5 空格缩进）");
        assertTrue(lines.get(2).contains("b"));
    }

    @Test
    void doneCardTruncatesLongOutput() {
        String longOutput = "line\n".repeat(400);
        ToolCallDisplay card = new ToolCallDisplay("Bash", "command=\"ls -R\"");
        List<String> lines = card.appendDone(ToolResult.success(longOutput), 0);
        assertEquals(ToolCallDisplay.MAX_DISPLAY_LINES + 2, lines.size(), "300 行内容 + 截断 marker + 脚注");
        boolean hasMarker = lines.stream().anyMatch(l -> l.contains("输出过长"));
        assertTrue(hasMarker, "应含截断 marker");
        assertTrue(lines.contains("  ⎿  …（输出过长，已截断）"));
    }

    @Test
    void doneCardEmptyContentShowsPlaceholder() {
        ToolCallDisplay empty = new ToolCallDisplay("Bash", "command=\"true\"");
        List<String> lines = empty.appendDone(ToolResult.success(""), 0);
        assertTrue(lines.get(0).contains("无返回结果"), "空内容出占位行：" + lines.get(0));

        ToolCallDisplay nullResult = new ToolCallDisplay("Bash", "command=\"true\"");
        List<String> nullLines = nullResult.appendDone(null, 0);
        assertTrue(nullLines.get(0).contains("无返回结果"), "null 结果出占位行");
    }

    @Test
    void doneCardAppendsDurationFooter() {
        ToolCallDisplay card = new ToolCallDisplay("Bash", "command=\"echo hi\"");
        List<String> lines = card.appendDone(ToolResult.success("hi\n"), 823);
        String footer = lines.get(lines.size() - 1);
        assertTrue(footer.contains("  ⎿  "), "脚注 ⎿ 前缀：" + footer);
        assertTrue(footer.contains("(823ms)"));
        assertTrue(footer.contains(ToolCallDisplay.STYLE_DIM), "脚注为灰色");
    }

    @Test
    void formatDurationFormatsMsAndSeconds() {
        assertEquals("0ms", ToolCallDisplay.formatDuration(0));
        assertEquals("823ms", ToolCallDisplay.formatDuration(823));
        assertEquals("2.3s", ToolCallDisplay.formatDuration(2300));
        assertEquals("5.0s", ToolCallDisplay.formatDuration(5000));
        assertEquals("0ms", ToolCallDisplay.formatDuration(-5), "负值按 0 处理");
    }

    @Test
    void doneCardPrefersDisplayOverContent() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendDone(
                ToolResult.success("机密内容").withDisplay("返回 2 行（L1-2）"), 0);
        assertEquals(2, lines.size(), "摘要行 + 脚注");
        assertTrue(lines.get(0).contains("返回 2 行"));
        assertFalse(lines.get(0).contains("机密内容"), "display 非空时不应渲染 content");
    }

    @Test
    void doneCardColorsDiffPrefixLines() {
        ToolCallDisplay card = new ToolCallDisplay("WriteFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendDone(
                ToolResult.success("已写入 a.txt（5 字符）").withDisplay("已写入 a.txt（5 字符）\n+ hi\n- bye"), 0);
        assertTrue(lines.get(1).contains(ToolCallDisplay.STYLE_OK), "+ 行应为绿色：" + lines.get(1));
        assertTrue(lines.get(1).contains("+ hi"));
        assertTrue(lines.get(2).contains(ToolCallDisplay.STYLE_ERR), "- 行应为红色：" + lines.get(2));
        assertTrue(lines.get(2).contains("- bye"));
    }

    @Test
    void doneCardEmptyDisplayFallsBackToContent() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendDone(ToolResult.success("正文").withDisplay(""), 0);
        assertTrue(lines.get(0).contains("正文"), "display 为空串时应回退渲染 content");
    }

    @Test
    void doneCardFailureIgnoresDisplay() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"nope.txt\"");
        List<String> lines = card.appendDone(
                ToolResult.failure("文件不存在").withDisplay("返回 0 行"), 0);
        assertTrue(lines.get(0).contains("文件不存在"), "失败结果即使带 display 仍渲染错误正文：" + lines.get(0));
        assertTrue(lines.get(0).contains(ToolCallDisplay.STYLE_ERR));
    }

    @Test
    void doneCardLongDisplayTruncated() {
        String display = "已写入 a.txt\n" + ("+ x\n".repeat(400));
        ToolCallDisplay card = new ToolCallDisplay("WriteFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendDone(ToolResult.success("确认").withDisplay(display), 0);
        assertEquals(ToolCallDisplay.MAX_DISPLAY_LINES + 2, lines.size(), "300 行 + 截断 marker + 脚注");
        assertTrue(lines.contains("  ⎿  …（输出过长，已截断）"));
    }
}
