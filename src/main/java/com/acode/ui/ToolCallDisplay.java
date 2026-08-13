package com.acode.ui;

import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工具调用卡片：把一次工具调用渲染成输出区若干行。
 * 生命周期：appendRunning() 画「进行中」→ 执行结束后 appendDone() 更新为成功/失败 + 结果摘要。
 * 卡片整体由 StreamPrinter 按批替换，本类只负责渲染与记录自身行数。
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
    private int lineCount;

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

    /** 在输出区末尾画「进行中」卡片 */
    public void appendRunning(OutputPane output) {
        String line = "▸ " + STYLE_NAME + toolName + RESET
                + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")")
                + " 运行中…";
        append(output, STYLE_RUNNING + line + RESET);
    }

    /** 在输出区末尾画「终态」卡片：成功/失败 + 结果摘要 */
    public void appendDone(OutputPane output, ToolResult result) {
        String head = "▸ " + STYLE_NAME + toolName + RESET
                + (paramsSummary.isEmpty() ? "" : "(" + paramsSummary + ")");
        boolean ok = result != null && result.isSuccess();
        String style = ok ? STYLE_OK : STYLE_ERR;
        String status = ok ? "成功" : "失败";
        String summary = result != null ? collapse(result.content()) : "（无返回结果）";
        append(output, style + head + " " + status + "：" + summary + RESET);
    }

    public int lineCount() {
        return lineCount;
    }

    private void append(OutputPane output, String line) {
        output.appendLine(line);
        lineCount++;
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
