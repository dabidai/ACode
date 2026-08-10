package com.acode.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * 终端生命周期与全屏重绘。全屏布局：输出区占第 1 ~ height-1 行，输入行固定在最底行。
 * 布局自绘用 ANSI：\033[H 光标回原点、\033[J 清屏。
 */
public class AcodeTerminal implements AutoCloseable {

    private final Terminal terminal;

    private AcodeTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * 打开系统终端并进入 raw 模式。
     * 无可用终端（或 JLine 静默回退到不支持光标绘制的 dumb 终端）时抛 IllegalStateException。
     */
    public static AcodeTerminal open() {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(false)
                    .encoding("UTF-8")
                    .build();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("无法初始化终端（需在真实终端中运行）：" + e.getMessage(), e);
        }
        if (terminal.getStringCapability(InfoCmp.Capability.cursor_address) == null
                || terminal.getHeight() <= 0) {
            closeQuietly(terminal);
            throw new IllegalStateException("未检测到支持全屏绘制的终端，请在真实终端中运行 ACode");
        }
        terminal.enterRawMode();
        return new AcodeTerminal(terminal);
    }

    private static void closeQuietly(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException ignored) {
            // 尽力关闭
        }
    }

    public Terminal terminal() {
        return terminal;
    }

    public int height() {
        return terminal.getHeight();
    }

    public int width() {
        return terminal.getWidth();
    }

    public void write(String text) {
        terminal.writer().print(text);
    }

    public void flush() {
        terminal.writer().flush();
    }

    /** 清屏并把光标移到左上角。 */
    public void clearScreen() {
        write("\033[H\033[J");
    }

    /** 移动光标到指定行列（均从 1 起）。 */
    public void moveTo(int row, int col) {
        write("\033[" + row + ";" + col + "H");
    }

    /** 全量重绘：重画输出区可见窗口，然后把光标定位到最底输入行。 */
    public void repaint(OutputPane output) {
        clearScreen();
        int h = height();
        int w = width();
        for (String line : output.visibleLines(h - 1)) {
            write(truncateToWidth(line, w));
            write("\r\n");
        }
        moveTo(h, 1);
        flush();
    }

    /** 按码点截断到终端宽度，避免超长行折行破坏行计数，也不切断代理对。 */
    private static String truncateToWidth(String line, int width) {
        if (line.codePointCount(0, line.length()) <= width) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, width));
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (IOException e) {
            // 退出时尽力恢复终端；失败可忽略
        }
    }
}
