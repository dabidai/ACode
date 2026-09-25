package com.acode.ui;

import com.acode.command.CommandRegistry;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;

import java.util.function.Supplier;
import java.util.List;

/**
 * 输入区：Enter 提交、Shift+Enter 换行（多行输入）、上下方向键翻输入历史、光标移动。
 * 基于 JLine3 LineReader；粘贴 20 行代码由括号粘贴模式保留原样。
 * 启用擦除行选项（R2）：接受输入时 JLine 擦除原输入行、不写换行，由应用统一追加「● 输入」，
 * 避免主屏下输入原文与应用追加行双写。
 */
public class InputPane {

    /** 输入行提示符。单行、以 {@code >} 开头，后接一空格。 */
    public static final String DEFAULT_PROMPT = "> ";

    /** 自定义 widget：向 buffer 插入换行，实现「Shift+Enter 不提交只换行」。 */
    private static final String NEWLINE_WIDGET = "acode-newline";

    private final ResizeAwareLineReader reader;
    private final String prompt;

    public InputPane(Terminal terminal, String prompt, CommandRegistry registry) {
        this.prompt = prompt;
        this.reader = new ResizeAwareLineReader(terminal, "acode");
        reader.option(LineReader.Option.ERASE_LINE_ON_FINISH, true);
        reader.setCompleter(new SlashCompleter(registry));
        bindKeys();
    }

    private void bindKeys() {
        reader.getWidgets().put(NEWLINE_WIDGET, () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(LineReader.ACCEPT_LINE), "\r");
        // Shift+Enter（CSI-u / 传统 xterm 序列）与 Ctrl+Enter 均插入换行
        main.bind(new Reference(NEWLINE_WIDGET), "\033[13;2u", "\033[1;2;13~", "\033[13;5u");
    }

    /**
     * 阻塞读取一行（可含换行）。输入框为空时按 Ctrl+C / Ctrl+D
     * 抛 {@link org.jline.reader.UserInterruptException} / {@link org.jline.reader.EndOfFileException}。
     */
    public String readLine() {
        return reader.readLine(prompt);
    }

    /** 阻塞读取一行，使用自定义提示符；提示符可多行、可含 ANSI（JLine 内部按 {@code fromAnsi} 解析）。 */
    public String readLine(String prompt) {
        return reader.readLine(prompt);
    }

    /** 活动读取期间按新终端宽度重建提示符，并同步重绘底部状态区。 */
    public String readLine(Supplier<String> promptSupplier, Runnable footerRedraw) {
        return reader.readLine(promptSupplier, footerRedraw);
    }

    /** Read while the whole input frame is owned by JLine's bottom status area. */
    public String readLineFramed(Supplier<String> mode, Supplier<String> footer,
                                 Runnable replayHistory, Supplier<List<String>> historyLines) {
        return reader.readLineFramed(mode, footer, replayHistory, historyLines);
    }

    /** 最近一次动态读取期间是否收到过 WINCH；主循环据此丢弃旧尺寸计算出的钉底回退量。 */
    public boolean wasResizedDuringLastRead() {
        return reader.wasResizedDuringLastRead();
    }
}
