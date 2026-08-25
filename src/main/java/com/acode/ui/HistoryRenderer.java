package com.acode.ui;

import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/** 历史消息渲染：text 块原样拼接、工具块压缩为单行摘要（恢复/加载会话显示用）。 */
public final class HistoryRenderer {

    private HistoryRenderer() {
    }

    /** 消息渲染为文本：text 块原样拼接，tool_use / tool_result 压缩为单行摘要。 */
    public static String renderHistoryMessage(ChatMessage message) {
        StringBuilder text = new StringBuilder();
        List<String> extras = new ArrayList<>();
        for (ContentBlock block : message.blocks()) {
            switch (block) {
                case TextBlock t -> text.append(t.text());
                case ToolUseBlock tu -> {
                    String params = ToolCallDisplay.summarizeParams(tu.input());
                    extras.add("[工具调用 " + tu.name()
                            + (params.isEmpty() ? "" : "(" + params + ")") + "]");
                }
                case ToolResultBlock tr -> {
                    String summary = collapseOneLine(tr.content(), 80);
                    extras.add("[工具结果 " + (tr.isError() ? "失败" : "成功")
                            + (summary.isEmpty() ? "" : "：" + summary) + "]");
                }
            }
        }
        if (text.isEmpty() && extras.isEmpty()) {
            return "";
        }
        String result = text.toString();
        if (!extras.isEmpty()) {
            if (!result.isEmpty()) {
                result += "\n";
            }
            result += String.join("\n", extras);
        }
        return result;
    }

    /** 多行/超长文本压缩为单行摘要（恢复会话显示用）。 */
    private static String collapseOneLine(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
