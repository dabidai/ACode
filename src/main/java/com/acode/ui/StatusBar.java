package com.acode.ui;

import org.jline.utils.WCWidth;

import java.util.Locale;

/**
 * 状态栏格式化：输入框上下框线里那两行（权限模式行、模型信息行）与分隔线。纯格式化，无终端 I/O。
 * 所有返回值保证**显示宽度 ≤ width**（CJK 按 wcwidth 占 2 列）：框线一旦折行成两行，
 * 等待帧的上移行数就会与实际屏幕对不上。
 */
public final class StatusBar {

    private static final String SEP = " · ";
    /** ctx 进度条格数。 */
    public static final int BAR_CELLS = 10;
    /**
     * 模式行第二段。本项目没有 Shift+Tab 切档键位（PR #2 的 InputPane.RowRewriter 未采纳），
     * 所以这里给的是真实可用的命令，不写做不到的键位提示——命令名是 ch09 改名后的
     * {@code /permission}（无参只查看规则，带参数才切档），不是旧名 {@code /permission-mode}。
     */
    private static final String MODE_HINT = " · /permission <模式>";

    private StatusBar() {
    }

    /** 权限模式行：{@code [default] · /permission <模式>}（模式名亮黄、提示暗灰）。 */
    public static String modeLine(String mode, int width) {
        String plain = "[" + mode + "]" + MODE_HINT;
        if (displayWidth(plain) > width) {
            return truncate(plain, width);
        }
        return AnsiPalette.MODE + "[" + mode + "]" + AnsiPalette.RESET
                + AnsiPalette.DIM + MODE_HINT + AnsiPalette.RESET;
    }

    /**
     * 模型信息行：{@code model · ctx ▓▓▓░░░░░░░ 12% · 项目路径}。
     * ctxFraction 为上下文占用比例 [0,1]；放不下时从**左侧**截断路径（保留最深的目录名，
     * 盘符与上层目录才是可牺牲的部分）。
     */
    public static String infoLine(String model, double ctxFraction, String projectPath, int width) {
        int filled = ctxBarFilled(ctxFraction);
        String percent = ctxPercentText(ctxFraction);
        String bar = "▓".repeat(filled) + "░".repeat(BAR_CELLS - filled);
        String head = model + SEP + "ctx " + bar + " " + percent + SEP;
        int room = width - displayWidth(head) - 1; // 1 列留给 …
        String shownPath = displayWidth(projectPath) > room
                ? (room > 0 ? "…" + tailFitting(projectPath, room) : "…")
                : projectPath;
        String plain = head + shownPath;
        if (displayWidth(plain) > width) {
            return truncate(plain, width); // 极窄终端连省略号都放不下：宁可丢色截断，也不能折行
        }
        return AnsiPalette.MODEL + model + AnsiPalette.RESET + SEP
                + "ctx " + AnsiPalette.BAR + "▓".repeat(filled)
                + AnsiPalette.DIM + "░".repeat(BAR_CELLS - filled) + AnsiPalette.RESET
                + " " + percent + SEP + shownPath;
    }

    /** 全宽 ─ 分隔线（暗色） */
    public static String divider(int width) {
        return AnsiPalette.DIM + "─".repeat(Math.max(1, width)) + AnsiPalette.RESET;
    }

    /** 进度条亮格数（共 {@value BAR_CELLS} 格，四舍五入）；占用非零但不足半格时至少亮 1 格，否则永远看着像空的。 */
    public static int ctxBarFilled(double ctxFraction) {
        double clamped = Math.max(0, Math.min(1, ctxFraction));
        int filled = (int) Math.round(clamped * BAR_CELLS);
        return filled == 0 && clamped > 0 ? 1 : filled;
    }

    /** 百分比文本：≥10% 取整，(0,10%) 保留一位小数——大窗口下取整会长期显示 0%、看不出变化。 */
    public static String ctxPercentText(double ctxFraction) {
        double percent = Math.max(0, Math.min(100, ctxFraction * 100));
        if (percent >= 10) {
            return Math.round(percent) + "%";
        }
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /** 文本在终端上占的列数（CJK 等宽字符占 2 列）。传入的应是**不含 ANSI 的原文**。 */
    static int displayWidth(String plain) {
        int w = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            w += Math.max(0, WCWidth.wcwidth(cp));
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 能放进 width 列的前缀长度（字符数）；不切断宽字符。 */
    private static int fit(String plain, int width) {
        int used = 0;
        int i = 0;
        while (i < plain.length()) {
            int cp = plain.codePointAt(i);
            int w = Math.max(0, WCWidth.wcwidth(cp));
            if (used + w > width) {
                break;
            }
            used += w;
            i += Character.charCount(cp);
        }
        return i;
    }

    /** 丢色截断：只保留能放进 width 列的前缀（全宽字符一字放不下时返回空串，也不折行）。 */
    private static String truncate(String plain, int width) {
        return plain.substring(0, fit(plain, Math.max(0, width)));
    }

    /** 从右往左取能放进 width 列的尾部（路径放不下时保留最深的目录名）。 */
    private static String tailFitting(String plain, int width) {
        int used = 0;
        int i = plain.length();
        while (i > 0) {
            int cp = plain.codePointBefore(i);
            int w = Math.max(0, WCWidth.wcwidth(cp));
            if (used + w > width) {
                break;
            }
            used += w;
            i -= Character.charCount(cp);
        }
        return plain.substring(i);
    }
}
