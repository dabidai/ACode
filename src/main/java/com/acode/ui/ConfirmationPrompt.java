package com.acode.ui;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.io.Writer;
import java.util.function.Function;

/**
 * 工具确认提示：渲染「要执行 X …？[y/n]」为提交行，循环读一行应答。
 * y/yes 批准、n/no 拒绝、其他重问、Ctrl+C/EOF 视为拒绝。每次应答都以提交行写回滚，
 * 提示与结果留在历史、无活跃区残影。读行器抽象为 prompt→line 函数，便于测试注入。
 */
public class ConfirmationPrompt {

    /** 读行提示符（无冒号分隔的空格缩进，与普通输入区「> 」区分）。 */
    private static final String PROMPT = "  [y/n] ";

    private final Function<String, String> reader;
    private final LiveRegionRenderer live;
    private final Writer writer;

    public ConfirmationPrompt(Function<String, String> reader, LiveRegionRenderer live, Writer writer) {
        this.reader = reader;
        this.live = live;
        this.writer = writer;
    }

    /** 渲染确认提示并循环读行应答；返回是否批准（Ctrl+C/EOF 视为拒绝）。 */
    public boolean ask(String toolName, String argsSummary) {
        while (true) {
            live.appendCommitted(writer, promptLine(toolName, argsSummary));
            String line = readAnswer();
            if (isYes(line)) {
                live.appendCommitted(writer, "（已批准执行「" + toolName + "」）");
                return true;
            }
            if (isNo(line)) {
                live.appendCommitted(writer, "（已拒绝执行「" + toolName + "」）");
                return false;
            }
            if (line == null) {
                // Ctrl+C / EOF 被 JLine 转为异常：按取消拒绝
                live.appendCommitted(writer, "（已取消）");
                return false;
            }
            live.appendCommitted(writer, "（请输入 y 或 n）");
        }
    }

    /** 读一行应答；Ctrl+C / EOF 抛异常时返回 null（表示取消）。 */
    private String readAnswer() {
        try {
            return reader.apply(PROMPT);
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    static boolean isYes(String line) {
        if (line == null) {
            return false;
        }
        String a = line.trim().toLowerCase();
        return "y".equals(a) || "yes".equals(a);
    }

    static boolean isNo(String line) {
        if (line == null) {
            return false;
        }
        String a = line.trim().toLowerCase();
        return "n".equals(a) || "no".equals(a);
    }

    /** 提示行：工具名 + 参数摘要 + 问句；摘要为空时不带括号。 */
    static String promptLine(String toolName, String argsSummary) {
        String summary = argsSummary == null ? "" : argsSummary.trim();
        return summary.isEmpty()
                ? "要执行「" + toolName + "」？[y/n]"
                : "要执行「" + toolName + "（" + summary + "）」？[y/n]";
    }
}
