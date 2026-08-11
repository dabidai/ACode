package com.acode.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;

/**
 * 输入区：Enter 提交、Shift+Enter 换行（多行输入）、上下方向键翻输入历史、光标移动。
 * 基于 JLine3 LineReader；粘贴 20 行代码由括号粘贴模式保留原样。
 * 鼠标滚轮：进入鼠标追踪后把滚轮事件转成输出区滚动（而非滚动终端 scrollback，
 * 否则会破坏全屏绝对坐标导致增量渲染错乱）。
 */
public class InputPane {

    /** 自定义 widget：向 buffer 插入换行，实现「Shift+Enter 不提交只换行」。 */
    private static final String NEWLINE_WIDGET = "acode-newline";
    private static final String SCROLL_UP_WIDGET = "acode-scroll-up";
    private static final String SCROLL_DOWN_WIDGET = "acode-scroll-down";

    /** 滚动回调：PageUp/PageDown 触发，由上层决定滚动步长并局部重绘输出区。 */
    public interface ScrollHandler {
        void scrollUp();

        void scrollDown();
    }

    private final LineReader reader;
    private final String prompt;
    private final ScrollHandler scrollHandler;

    public InputPane(Terminal terminal, String prompt, ScrollHandler scrollHandler) {
        this.prompt = prompt;
        this.scrollHandler = scrollHandler;
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("acode")
                .option(LineReader.Option.MOUSE, true)
                .build();
        bindKeys();
        bindMouse();
    }

    private void bindKeys() {
        reader.getWidgets().put(NEWLINE_WIDGET, () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        if (scrollHandler != null) {
            reader.getWidgets().put(SCROLL_UP_WIDGET, () -> {
                runScroll(scrollHandler::scrollUp);
                return true;
            });
            reader.getWidgets().put(SCROLL_DOWN_WIDGET, () -> {
                runScroll(scrollHandler::scrollDown);
                return true;
            });
        }
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        main.bind(new Reference(LineReader.ACCEPT_LINE), "\r");
        // Shift+Enter（CSI-u / 传统 xterm 序列）与 Ctrl+Enter 均插入换行
        main.bind(new Reference(NEWLINE_WIDGET), "\033[13;2u", "\033[1;2;13~", "\033[13;5u");
        // PageUp/PageDown：回看/回到底部查看完整聊天内容
        if (scrollHandler != null) {
            main.bind(new Reference(SCROLL_UP_WIDGET), "\033[5~");
            main.bind(new Reference(SCROLL_DOWN_WIDGET), "\033[6~");
        }
    }

    /** 覆盖 JLine 内置 mouse widget：滚轮事件转输出区滚动，其余鼠标事件消费掉，避免干扰输入。 */
    private void bindMouse() {
        if (scrollHandler == null) {
            return;
        }
        reader.getWidgets().put(LineReader.MOUSE, () -> {
            MouseEvent event = reader.readMouseEvent();
            if (event != null) {
                if (event.getButton() == MouseEvent.Button.WheelUp) {
                    runScroll(scrollHandler::scrollUp);
                } else if (event.getButton() == MouseEvent.Button.WheelDown) {
                    runScroll(scrollHandler::scrollDown);
                }
            }
            return true;
        });
    }

    /**
     * 保存/恢复光标包裹滚动重绘：JLine 用相对移动追踪光标位置，绕过它移动光标会破坏其状态，
     * 导致下次 redisplay 输入行时错位。\033[s/\033[u 保证实际光标回到 JLine 认为的位置。
     */
    private void runScroll(Runnable action) {
        reader.getTerminal().writer().write("\033[s");
        action.run();
        reader.getTerminal().writer().write("\033[u");
        reader.getTerminal().writer().flush();
    }

    /**
     * 阻塞读取一行（可含换行）。输入框为空时按 Ctrl+C / Ctrl+D
     * 抛 {@link org.jline.reader.UserInterruptException} / {@link org.jline.reader.EndOfFileException}。
     */
    public String readLine() {
        return reader.readLine(prompt);
    }
}
