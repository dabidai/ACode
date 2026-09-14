package com.acode.ui;

/**
 * 终端 ANSI 色板：状态栏/页脚那套配色的唯一出处（出处为 PR #2 的 {@code ui/StatusBar}）。
 * 单独成类而不放在 {@link StatusBar} 里：{@link ToolCallDisplay} 与
 * {@link MarkdownRenderer} 也要引用同一套值，从 StatusBar 取色会造成依赖倒置。
 */
public final class AnsiPalette {

    /** 暗色辅助：分隔线、次要提示 */
    public static final String DIM = "\033[90m";
    /** 权限模式 */
    public static final String MODE = "\033[1;33m";
    /** 模型名（亮青） */
    public static final String MODEL = "\033[1;36m";
    /** 上下文进度条 */
    public static final String BAR = "\033[36m";
    /** 复位 */
    public static final String RESET = "\033[0m";

    private AnsiPalette() {
    }
}
