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
 * 鼠标：进入按钮追踪（含按键拖动）后把滚轮转成输出区逐行滚动、滚动条点击/拖动转成跳转，
 * 而不是滚动终端 scrollback（否则会破坏全屏绝对坐标导致增量渲染错乱）。
 */
public class InputPane {

    /** 自定义 widget：向 buffer 插入换行，实现「Shift+Enter 不提交只换行」。 */
    private static final String NEWLINE_WIDGET = "acode-newline";
    private static final String SCROLL_UP_WIDGET = "acode-scroll-up";
    private static final String SCROLL_DOWN_WIDGET = "acode-scroll-down";

    /** 滚动回调：滚轮/翻页传步长，滚动条点击拖动传鼠标在输出区内的行号。 */
    public interface ScrollHandler {
        void scroll(int deltaLines);

        /** 鼠标在输出区内 1-based 行号（最右列滚动条），由上层换算成目标滚动位置。 */
        void scrollToY(int y);
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
        // JLine 的 Option.MOUSE 只开 Normal（按键+滚轮）；滚动条拖动需要按键移动跟踪
        terminal.trackMouse(Terminal.MouseTracking.Button);
    }

    private void bindKeys() {
        reader.getWidgets().put(NEWLINE_WIDGET, () -> {
            reader.getBuffer().write("\n");
            return true;
        });
        if (scrollHandler != null) {
            reader.getWidgets().put(SCROLL_UP_WIDGET, () -> {
                runScroll(() -> scrollHandler.scroll(pageLines()));
                return true;
            });
            reader.getWidgets().put(SCROLL_DOWN_WIDGET, () -> {
                runScroll(() -> scrollHandler.scroll(-pageLines()));
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

    /** 覆盖 JLine 内置 mouse widget：滚轮转滚动、滚动条列点击/拖动转跳转，其余消费掉避免干扰输入。 */
    private void bindMouse() {
        if (scrollHandler == null) {
            return;
        }
        // Windows 终端不提供 key_mouse capability，JLine 默认会把 mouse 绑定跳过，
        // 鼠标序列（\033[M X10 / \033[< SGR）会泄漏进输入框。手动绑定到 mouse widget。
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference(LineReader.MOUSE), "\033[M", "\033[<");
        reader.getWidgets().put(LineReader.MOUSE, () -> {
            MouseEvent event = reader.readMouseEvent();
            if (event == null) {
                return true;
            }
            switch (event.getType()) {
                case Wheel:
                    if (event.getButton() == MouseEvent.Button.WheelUp) {
                        runScroll(() -> scrollHandler.scroll(WHEEL_STEP));
                    } else if (event.getButton() == MouseEvent.Button.WheelDown) {
                        runScroll(() -> scrollHandler.scroll(-WHEEL_STEP));
                    }
                    break;
                case Pressed:
                case Dragged:
                    onScrollbarPress(event);
                    break;
                default:
                    // Released / Moved：拖动松手或无关移动，忽略
                    break;
            }
            return true;
        });
    }

    /** 滚动条列（最右列）上的按下/拖动：转发行号，由上层换算滚动位置；点在内容区忽略。 */
    private void onScrollbarPress(MouseEvent event) {
        int w = reader.getTerminal().getWidth();
        if (event.getX() != w) {
            return;
        }
        int outputArea = outputAreaLines();
        int y = event.getY();
        if (y < 1 || y > outputArea) {
            return;
        }
        runScroll(() -> scrollHandler.scrollToY(y));
    }

    /** 滚轮单格行数（逐行滚动，替代换页更精细）。 */
    private static final int WHEEL_STEP = 3;

    /** 一屏可见输出区行数，PageUp/PageDown 翻一屏用。 */
    private int pageLines() {
        return Math.max(1, reader.getTerminal().getHeight() - 2);
    }

    /** 输出区可用行数（与 AcodeTerminal.outputArea 一致）。 */
    private int outputAreaLines() {
        int h = reader.getTerminal().getHeight();
        return Math.max(1, h >= 3 ? h - 2 : h - 1);
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
