package com.acode.ui;

import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具调用卡片：把一次工具调用渲染成若干行。渲染与写入解耦——本类只产生渲染行，
 * 由 StreamPrinter 追加进回滚。
 * 生命周期：appendRunning() 画「● 工具名(参数)」（静态历史记录，先追加）→ 执行结束后
 * appendDone() 渲染输出块（首行 ⎿ 着色 + 后续行缩进 + 耗时脚注）作为终态块追加进回滚。
 */
public class ToolCallDisplay {

    static final String STYLE_RUNNING = "\033[33m";  // 黄色：进行中
    public static final String STYLE_OK = "\033[32m";       // 绿色：成功
    public static final String STYLE_ERR = "\033[31m";      // 红色：失败
    static final String STYLE_NAME = "\033[1;36m";   // 亮青色：工具名
    static final String STYLE_DIM = "\033[90m";      // 灰色：耗时脚注
    static final String RESET = "\033[0m";

    static final int MAX_PARAM_LENGTH = 40;
    static final int MAX_DISPLAY_LINES = 300;

    private final String toolName;
    private final String paramsSummary;
    private List<String> renderedLines = List.of();
    /** 当前 renderedLines 已追加进回滚的行数（追加式：每行只写一次，去重用）。 */
    private int screenAppended = 0;

    public ToolCallDisplay(String toolName, String paramsSummary) {
        this.toolName = toolName;
        this.paramsSummary = paramsSummary;
    }

    /** 参数对象 → 紧凑摘要：`name="value"` 空格分隔，长值截断 */
    public static String summarizeParams(JsonNode input) {
        if (input == null || !input.isObject() || input.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            String text = value.isTextual() ? "\"" + value.asText() + "\"" : value.toString();
            if (text.length() > MAX_PARAM_LENGTH) {
                text = text.substring(0, MAX_PARAM_LENGTH - 1) + "…";
            }
            parts.add(entry.getKey() + "=" + text);
        }
        return String.join(" ", parts);
    }

    /** 「进行中」卡片渲染行；追加式下作为静态历史记录（「● 工具名」，终态块随后追加）。 */
    public List<String> appendRunning() {
        screenAppended = 0;
        String line = "● " + STYLE_NAME + toolName + RESET
                + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")");
        renderedLines = List.of(STYLE_RUNNING + line + RESET);
        return renderedLines;
    }

    /** 「终态」卡片渲染行：输出块（首行 ⎿ 成败着色 + 后续行缩进 + 截断 marker + 耗时脚注）。 */
    public List<String> appendDone(ToolResult result, long elapsedMs) {
        screenAppended = 0;
        List<String> block = new ArrayList<>();
        boolean ok = result != null && result.isSuccess();
        String content = result != null ? result.content() : null;
        if (content == null || content.isEmpty()) {
            block.add("  ⎿  （无返回结果）");
        } else {
            String[] parts = content.split("\\r?\\n", -1);
            int end = parts.length;
            while (end > 0 && parts[end - 1].isEmpty()) {
                end--; // 输出常以换行结尾，去掉末尾空行，保留中间空行
            }
            if (end == 0) {
                block.add("  ⎿  （无返回结果）");
            } else {
                int limit = Math.min(end, MAX_DISPLAY_LINES);
                for (int i = 0; i < limit; i++) {
                    String line = parts[i];
                    if (i == 0) {
                        block.add("  ⎿  " + (ok ? STYLE_OK : STYLE_ERR) + line + RESET);
                    } else {
                        block.add("     " + line);
                    }
                }
                if (end > MAX_DISPLAY_LINES) {
                    block.add("  ⎿  …（输出过长，已截断）");
                }
            }
        }
        block.add("  ⎿  " + STYLE_DIM + "(" + formatDuration(elapsedMs) + ")" + RESET);
        renderedLines = block;
        return renderedLines;
    }

    /** 耗时展示：<1s 毫秒（823ms），≥1s 秒一位小数（2.3s）；负值按 0 处理。 */
    static String formatDuration(long elapsedMs) {
        long ms = Math.max(elapsedMs, 0);
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** 当前渲染行数（活跃区与模型行数统计用）。 */
    public int lineCount() {
        return renderedLines.size();
    }

    /** 当前渲染行（运行中或终态）。 */
    List<String> renderedLines() {
        return renderedLines;
    }

    /** 已追加进回滚的行数（追加式去重用）。 */
    int screenAppended() {
        return screenAppended;
    }

    /** 记录追加进回滚的行数。 */
    void markAppended(int n) {
        screenAppended = Math.max(screenAppended, n);
    }
}
