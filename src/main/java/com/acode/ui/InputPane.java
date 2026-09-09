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

    /**
     * 在 JLine 编辑期间原地重写输入区上方某一行的能力。
     * 真实实现是 {@link InputPane#rewriteRowAboveInput(int, String)}（内部走
     * {@link org.jline.reader.LineReader#printAbove(String)}），调用方用方法引用接线；
     * 测试可注入假实现断言参数。
     */
    @FunctionalInterface
    public interface RowRewriter {
        void rewriteRowAboveInput(int rowsAbove, String text);
    }

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

    /**
     * 原地重写输入区上方第 rowsAbove 行（Shift+Tab 改模式提示行用）。
     * 必须借 {@code printAbove}：它先 {@code display.update(emptyList, 0)} 擦掉输入区、把光标放到
     * 输入区顶行第 0 列并清空 Display 缓存，再打印我们给的序列，最后从空缓存全量重绘 {@code >*}+buffer。
     * 若绕过它直接往终端 writer 写 ANSI，Display 仍以为光标在 buffer 末尾那一列，之后每次按键都按
     * 陈旧坐标定位、字符吃掉提示符，要等回车重开 readLine 才恢复。
     */
    public void rewriteRowAboveInput(int rowsAbove, String text) {
        reader.printAbove(rowRewriteSequence(rowsAbove, text));
    }

    /**
     * 构造净行位移为 0 的原地重写序列。三个易错点：
     * rowsAbove 从**输入区顶行**起算（printAbove 已把光标放到那里），故 buffer 折行/多行输入也正确；
     * text 后紧跟的 \r 化解「显示宽度恰等于终端宽度」时的 pending-wrap 幻影换行；
     * 结尾必须是 \r\n——只有以 \n 结尾 printAbove 才走 print 而非 println（否则多补一行、与随后的
     * 重绘失同步），同时它把光标送回输入区顶行第 0 列。
     */
    static String rowRewriteSequence(int rowsAbove, String text) {
        StringBuilder sb = new StringBuilder("\033[").append(rowsAbove).append("A")
                .append("\r\033[2K")
                .append(text)
                .append("\r");
        if (rowsAbove - 1 > 0) {
            sb.append("\033[").append(rowsAbove - 1).append("B");
        }
        sb.append("\r\n");
        return sb.toString();
    }
}
