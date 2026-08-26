package com.acode.ui;

import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatMessage message(ContentBlock... blocks) {
        return new ChatMessage(ASSISTANT, List.of(blocks));
    }

    @Test
    void plainTextAppendsAsIs() {
        assertEquals("你好，世界", HistoryRenderer.renderHistoryMessage(message(new TextBlock("你好，世界"))),
                "纯文本消息应原样返回");
    }

    @Test
    void emptyBlocksMessageReturnsEmptyString() {
        assertEquals("", HistoryRenderer.renderHistoryMessage(message()),
                "无内容块的消息应返回空串");
    }

    @Test
    void toolUseBlockCompressedToSingleLine() {
        ChatMessage m = message(new ToolUseBlock("id-1", "ReadFile",
                JSON.createObjectNode().put("file_path", "a.txt")));
        assertEquals("[工具调用 ReadFile(file_path=\"a.txt\")]",
                HistoryRenderer.renderHistoryMessage(m), "tool_use 应压缩为单行摘要");
    }

    @Test
    void toolUseWithoutParamsOmitsParentheses() {
        ChatMessage m = message(new ToolUseBlock("id-2", "Noop", JSON.createObjectNode()));
        assertEquals("[工具调用 Noop]", HistoryRenderer.renderHistoryMessage(m),
                "无参数时不应出现括号");
    }

    @Test
    void toolResultSuccessCollapsedToOneLine() {
        ChatMessage m = message(new ToolResultBlock("id-1", "文件内容", false));
        assertEquals("[工具结果 成功：文件内容]", HistoryRenderer.renderHistoryMessage(m),
                "成功结果应压缩为单行摘要");
    }

    @Test
    void toolResultErrorMarkedAsFailure() {
        ChatMessage m = message(new ToolResultBlock("id-1", "命令失败", true));
        assertEquals("[工具结果 失败：命令失败]", HistoryRenderer.renderHistoryMessage(m),
                "错误结果应标记为失败");
    }

    @Test
    void toolResultLongContentTruncatedAt80() {
        ChatMessage m = message(new ToolResultBlock("id-1", "x".repeat(100), false));
        assertEquals("[工具结果 成功：" + "x".repeat(80) + "…]",
                HistoryRenderer.renderHistoryMessage(m), "超长结果应截断到 80 字符并加省略号");
    }

    @Test
    void toolResultMultilineContentCollapsed() {
        ChatMessage m = message(new ToolResultBlock("id-1", "第一行\n第二行\n第三行", false));
        assertEquals("[工具结果 成功：第一行 第二行 第三行]",
                HistoryRenderer.renderHistoryMessage(m), "多行结果应折叠为单行（换行替换为空格）");
    }

    @Test
    void toolResultCrlfCollapsesToDoubleSpace() {
        ChatMessage m = message(new ToolResultBlock("id-1", "第一行\r\n第二行", false));
        assertEquals("[工具结果 成功：第一行  第二行]",
                HistoryRenderer.renderHistoryMessage(m), "CRLF 按先 \\n 后 \\r 依次替换，中间留双空格（记录现有行为）");
    }

    @Test
    void toolResultEmptyContentOmitsColon() {
        ChatMessage m = message(new ToolResultBlock("id-1", "", false));
        assertEquals("[工具结果 成功]", HistoryRenderer.renderHistoryMessage(m),
                "空结果不应出现冒号与空白摘要");
    }

    @Test
    void mixedBlocksKeepOrderWithNewlineSeparation() {
        ChatMessage m = message(
                new TextBlock("问题"),
                new ToolUseBlock("id-1", "Grep", JSON.createObjectNode().put("pattern", "abc")),
                new ToolResultBlock("id-1", "命中 1 处", false),
                new TextBlock("（后续文本）"));
        assertEquals("问题（后续文本）\n[工具调用 Grep(pattern=\"abc\")]\n[工具结果 成功：命中 1 处]",
                HistoryRenderer.renderHistoryMessage(m),
                "全部 text 块先原样拼接，工具摘要随后按序以换行分隔");
    }

    @Test
    void onlyToolBlocksRenderAsJoinedExtras() {
        ChatMessage m = message(
                new ToolUseBlock("id-1", "Bash", JSON.createObjectNode().put("command", "ls")),
                new ToolResultBlock("id-1", "输出", true));
        assertEquals("[工具调用 Bash(command=\"ls\")]\n[工具结果 失败：输出]",
                HistoryRenderer.renderHistoryMessage(m), "只有工具块时摘要应逐行列出");
    }
}
