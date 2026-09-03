package com.acode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;

/**
 * 输入区：Enter 提交、Shift+Enter 换行（多行输入）、上下方向键翻输入历史、光标移动。
 * 基于 JLine3 LineReader；粘贴 20 行代码由括号粘贴模式保留原样。
 * 启用擦除行选项（R2）：接受输入时 JLine 擦除原输入行、不写换行，由应用统一追加「● 输入」，
 * 避免主屏下输入原文与应用追加行双写。
 */
public class InputPane {

    /** 自定义 widget：向 buffer 插入换行，实现「Shift+Enter 不提交只换行」。 */
    private static final String NEWLINE_WIDGET = "acode-newline";

    /** 自定义 widget：Shift+Tab 循环切换权限模式。 */
    private static final String CYCLE_PERMISSION_WIDGET = "acode-cycle-permission";

    private final LineReader reader;
    private final String prompt;
    private Runnable cyclePermissionCallback;

    public InputPane(Terminal terminal, String prompt) {
        this(terminal, prompt, null);
    }

    public InputPane(Terminal terminal, String prompt, Completer completer) {
        this.prompt = prompt;
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("acode")
                .option(LineReader.Option.ERASE_LINE_ON_FINISH, true);
        if (completer != null) {
            builder.completer(completer);
        }
        this.reader = builder.build();
        bindKeys();
    }

    private void bindKeys() {
        reader.getWidgets().put(NEWLINE_WIDGET, () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        reader.getWidgets().put(CYCLE_PERMISSION_WIDGET, () -> {
            if (cyclePermissionCallback != null) {
                cyclePermissionCallback.run();
            }
            return true;
        });
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(LineReader.ACCEPT_LINE), "\r");
        // Shift+Enter（CSI-u / 传统 xterm 序列）与 Ctrl+Enter 均插入换行
        main.bind(new Reference(NEWLINE_WIDGET), "\033[13;2u", "\033[1;2;13~", "\033[13;5u");
        // Shift+Tab：循环切换权限模式
        main.bind(new Reference(CYCLE_PERMISSION_WIDGET), "\033[Z");
    }

    /** 注入 Shift+Tab 回调：每次按下时调用，用于循环切换权限模式并显示通知。 */
    public void setCyclePermissionCallback(Runnable callback) {
        this.cyclePermissionCallback = callback;
    }

    /**
     * 阻塞读取一行（可含换行）。输入框为空时按 Ctrl+C / Ctrl+D
     * 抛 {@link org.jline.reader.UserInterruptException} / {@link org.jline.reader.EndOfFileException}。
     */
    public String readLine() {
        return reader.readLine(prompt);
    }

    /** 阻塞读取一行，使用自定义提示符（工具确认提示等需区分场景时用）。 */
    public String readLine(String prompt) {
        return reader.readLine(prompt);
    }
}
