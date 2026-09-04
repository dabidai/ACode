package com.acode.ui;

import com.acode.permission.PermissionMode;

/**
 * 状态栏格式化：生成单行状态栏 + 分隔线，用于每轮对话前的"轮次标题"。
 * 纯格式化，无终端 I/O。
 */
public final class StatusBar {

    private static final String STYLE_DIM = "\033[90m";
    private static final String STYLE_MODE = "\033[1;33m";
    private static final String STYLE_MODEL = "\033[1;36m";
    private static final String STYLE_BAR = "\033[36m";
    private static final String RESET = "\033[0m";
    private static final String SEP = " · ";
    /** ctx 进度条格数。 */
    private static final int BAR_CELLS = 10;

    private StatusBar() {}

    /**
     * 权限模式行：[mode] Shift+Tab to [next]
     */
    public static String modeLine(String mode, String nextMode, int width) {
        String plain = "[" + mode + "] Shift+Tab to [" + nextMode + "]";
        if (plain.length() <= width) {
            return STYLE_MODE + "[" + mode + "]" + RESET
                    + STYLE_DIM + " Shift+Tab to [" + nextMode + "]" + RESET;
        }
        return plain.substring(0, width);
    }

    /**
     * 模型信息行：{@code model · ctx ▓▓▓░░░░░░░ 12% · 项目全路径}。
     * ctxFraction 为上下文占用比例 [0,1]；超出 width 时从**左侧**截断路径（保留最深的目录名，
     * 盘符与上层目录才是可牺牲的部分）。
     */
    public static String infoLine(String model, double ctxFraction, String projectPath, int width) {
        int filled = ctxBarFilled(ctxFraction);
        String percent = ctxPercentText(ctxFraction);
        String bar = "▓".repeat(filled) + "░".repeat(BAR_CELLS - filled);
        String head = model + SEP + "ctx " + bar + " " + percent + SEP;
        int room = width - head.length() - 1; // 1 列留给 …
        String shownPath = projectPath.length() > room
                ? (room > 0 ? "…" + projectPath.substring(projectPath.length() - room) : "…")
                : projectPath;
        String plain = head + shownPath;
        if (plain.length() > width) {
            // 极窄终端连省略号都放不下：宁可丢色截断，也不能折行——页脚多占一行会破坏等待帧的行数数学
            return plain.substring(0, Math.max(1, width));
        }
        return STYLE_MODEL + model + RESET + SEP
                + "ctx " + STYLE_BAR + "▓".repeat(filled) + STYLE_DIM + "░".repeat(BAR_CELLS - filled) + RESET
                + " " + percent + SEP + shownPath;
    }

    /** ctx 进度条亮格数（共 10 格，四舍五入）；占用非零但不足半格时至少亮 1 格，否则永远看着像空的。 */
    static int ctxBarFilled(double ctxFraction) {
        double clamped = Math.max(0, Math.min(1, ctxFraction));
        int filled = (int) Math.round(clamped * BAR_CELLS);
        return filled == 0 && clamped > 0 ? 1 : filled;
    }

    /** ctx 百分比文本：≥10% 取整，(0,10%) 保留一位小数——大窗口下取整会长期显示 0%、看不出变化。 */
    static String ctxPercentText(double ctxFraction) {
        double percent = Math.max(0, Math.min(100, ctxFraction * 100));
        if (percent >= 10) {
            return Math.round(percent) + "%";
        }
        return String.format("%.1f%%", percent);
    }

    /** 全宽 ─ 分隔线（dim 颜色） */
    public static String divider(int width) {
        return STYLE_DIM + "─".repeat(Math.max(1, width)) + RESET;
    }

    /** 循环中下一个模式名：default → acceptEdits → plan → bypassPermissions → default */
    public static String nextModeName(PermissionMode current) {
        PermissionMode[] cycle = {PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN, PermissionMode.BYPASS};
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == current) {
                return cycle[(i + 1) % cycle.length].configValue();
            }
        }
        return PermissionMode.DEFAULT.configValue();
    }
}
