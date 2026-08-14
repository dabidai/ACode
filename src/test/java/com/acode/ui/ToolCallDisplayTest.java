package com.acode.ui;

import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void runningCardShowsToolNameAndRunningState() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"a.txt\"");
        List<String> lines = card.appendRunning();
        assertEquals(1, card.lineCount());
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("ReadFile"));
        assertTrue(line.contains("运行中"));
        assertTrue(line.contains(ToolCallDisplay.STYLE_RUNNING));
    }

    @Test
    void successCardShowsResultSummary() {
        ToolCallDisplay card = new ToolCallDisplay("Bash", "command=\"echo hi\"");
        List<String> lines = card.appendDone(ToolResult.success("hi\n"));
        String line = lines.get(0);
        assertTrue(line.contains("成功"));
        assertTrue(line.contains("hi"), "结果摘要应包含输出：" + line);
        assertTrue(line.contains(ToolCallDisplay.STYLE_OK));
    }

    @Test
    void failureCardShowsErrorMessage() {
        ToolCallDisplay card = new ToolCallDisplay("ReadFile", "file_path=\"nope.txt\"");
        List<String> lines = card.appendDone(ToolResult.failure("文件不存在"));
        String line = lines.get(0);
        assertTrue(line.contains("失败"));
        assertTrue(line.contains("文件不存在"));
        assertTrue(line.contains(ToolCallDisplay.STYLE_ERR));
    }

    @Test
    void multilineResultCollapsedToSingleLine() {
        ToolCallDisplay card = new ToolCallDisplay("Grep", "pattern=\"x\"");
        List<String> lines = card.appendDone(ToolResult.success("line1\nline2\nline3"));
        assertEquals(1, lines.size(), "多行结果应折叠为一行");
        assertTrue(lines.get(0).contains("line1 line2 line3"));
    }
}
