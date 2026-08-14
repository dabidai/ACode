package com.acode.ui;

import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工具调用卡片：把一次工具调用渲染成若干行。渲染与写入解耦——本类只产生渲染行，
 * 由 StreamPrinter 追加进回滚。
 * 生命周期：appendRunning() 画「⏳ 调用工具」（静态历史记录，先追加）→ 执行结束后
 * appendDone() 更新为成功/失败 + 结果摘要（作为终态行追加进回滚）。
 */
public class ToolCallDisplay {

    static final String STYLE_RUNNING = "\033[33m";  // 黄色：进行中
    static final String STYLE_OK = "\033[32m";       // 绿色：成功
    static final String STYLE_ERR = "\033[31m";      // 红色：失败
    static final String STYLE_NAME = "\033[1;36m";   // 亮青色：工具名
    static final String RESET = "\033[0m";

    static final int MAX_PARAM_LENGTH = 40;
    static final int MAX_RESULT_LENGTH = 200;

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

    /** 「进行中」卡片渲染行；追加式下作为静态历史记录（「曾调用」，终态行随后追加）。 */
    public List<String> appendRunning() {
        screenAppended = 0;
        String line = "⏳ 调用工具 " + STYLE_NAME + toolName + RESET
                + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")");
        renderedLines = List.of(STYLE_RUNNING + line + RESET);
        return renderedLines;
    }

    /** 「终态」卡片渲染行：成功/失败 + 结果摘要；由 StreamPrinter 追加进回滚。 */
    public List<String> appendDone(ToolResult result) {
        screenAppended = 0;
        String head = "▸ " + STYLE_NAME + toolName + RESET
                + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")");
        boolean ok = result != null && result.isSuccess();
        String style = ok ? STYLE_OK : STYLE_ERR;
        String status = ok ? "成功" : "失败";
        String summary = result != null ? collapse(result.content()) : "（无返回结果）";
        renderedLines = List.of(style + head + " " + status + "：" + summary + RESET);
        return renderedLines;
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

    /** 结果正文压缩为单行摘要：换行折叠、超长截断 */
    private static String collapse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > MAX_RESULT_LENGTH) {
            oneLine = oneLine.substring(0, MAX_RESULT_LENGTH) + "…";
        }
        return oneLine;
    }
}
