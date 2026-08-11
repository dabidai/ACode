package com.acode.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
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

    /** 清屏并清空 scrollback，把光标移到左上角。缩放窗口后 scrollback 偏移会破坏绝对行号，需一并清掉。 */
    public void clearScreen() {
        write("\033[H\033[2J\033[3J");
    }

    /** 移动光标到指定行列（均从 1 起）。 */
    public void moveTo(int row, int col) {
        write("\033[" + row + ";" + col + "H");
    }

    /** 上次绘制到屏幕的输出区内容（shadow buffer），用于逐行 diff 增量刷新。 */
    private String[] shadow = new String[0];
    private int lastW = -1;
    private int lastH = -1;

    /**
     * 增量重绘：输出区逐行 diff，只重写变化行（流式时通常只有尾部几行），不清屏 → 无闪动。
     * 分隔线每次重绘都强制重画（不靠 diff 跳过），确保长内容/缩放后始终可见。
     * 布局：输出区 1..h-2 行，h-1 行分隔线，h 行输入框；每次重绘清空输入行避免残留。
     */
    public void repaint(OutputPane output) {
        checkResize();
        drawOutputArea(output);
        moveTo(height(), 1);
        write("\033[K"); // 清空输入行，避免历史残留文字与输入框混淆
        flush();
    }

    /**
     * 仅重绘输出区与分隔线，不触碰底部输入行（JLine 正在使用，移动光标会干扰其 buffer 编辑）。
     * 滚动回看时使用；下一次 JLine 自绘会纠正光标位置。
     */
    public void repaintOutputArea(OutputPane output) {
        checkResize();
        drawOutputArea(output);
        flush();
    }

    /** 窗口尺寸变化时清屏并清 scrollback 重锚定，重置 shadow 强制全量重绘，避免缩放错位。 */
    private void checkResize() {
        int h = height();
        int w = width();
        if (h != lastH || w != lastW) {
            clearScreen();
            shadow = new String[0];
            lastH = h;
            lastW = w;
        }
    }

    private void drawOutputArea(OutputPane output) {
        int h = height();
        int w = width();
        boolean hasSeparator = h >= 3;
        int outputArea = Math.max(1, hasSeparator ? h - 2 : h - 1);
        // 原始行按终端宽度折行后取最后 outputArea 段，保证长行内容完整显示而非截断
        List<String> wrapped = new ArrayList<>();
        for (String line : output.visibleLines(outputArea)) {
            wrapped.addAll(wrap(line, w));
        }
        String[] rows = new String[outputArea];
        int from = Math.max(0, wrapped.size() - outputArea);
        for (int i = 0; i < outputArea; i++) {
            int idx = from + i;
            rows[i] = idx < wrapped.size() ? wrapped.get(idx) : "";
        }
        for (int i = 0; i < outputArea; i++) {
            String cur = rows[i];
            String old = i < shadow.length ? shadow[i] : null;
            if (!cur.equals(old)) {
                moveTo(i + 1, 1);
                if (!cur.isEmpty()) {
                    write(cur);
                }
                write("\033[K");
            }
        }
        shadow = rows;
        if (hasSeparator) {
            moveTo(h - 1, 1);
            write(separatorLine(w));
            write("\033[K");
        }
    }

    /** 分隔线：一行灰色横线，把输出区与底部输入框分开（类似 Claude Code）。 */
    private static String separatorLine(int w) {
        return "\033[90m" + "─".repeat(Math.max(1, w)) + "\033[0m";
    }

    /**
     * 按终端显示宽度折行：把长行拆成多段，每段显示宽度 ≤ width（避免超宽行折行破坏行计数、
     * 也避免行尾被截断丢内容）。宽度按 wcwidth 计算（CJK 等宽字符占 2 列），折点不切断
     * 宽字符与 ANSI 转义序列；SGR 颜色状态跨段延续（段间不补 RESET）。每段可直接写入屏幕。
     */
    static List<String> wrap(String line, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            out.add(line);
            return out;
        }
        if (line.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int disp = 0;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\033') {
                int j = i + 1;
                if (j < n && line.charAt(j) == '[') {
                    j++;
                    while (j < n && !isAnsiFinalByte(line.charAt(j))) {
                        j++;
                    }
                    j++;
                } else {
                    j++;
                }
                cur.append(line, i, j);
                i = j;
            } else {
                int cp = line.codePointAt(i);
                int w = WCWidth.wcwidth(cp);
                int cnt = Character.charCount(cp);
                if (w > 0 && disp + w > width) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    disp = 0;
                    if (w > width) {
                        i += cnt; // 单个字符超宽（罕见），跳过避免死循环
                        continue;
                    }
                }
                cur.append(line, i, i + cnt);
                disp += Math.max(0, w);
                i += cnt;
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isAnsiFinalByte(char c) {
        return c >= 0x40 && c <= 0x7e;
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
