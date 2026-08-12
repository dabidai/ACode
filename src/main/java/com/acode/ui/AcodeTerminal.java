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
        int n = output.lineCount();
        // 内容总折行高度 > 视口时才需要滚动条（并为此让出最右一列）；否则整宽使用
        boolean sbVisible = false;
        int contentW = w;
        if (w >= 2) {
            int totalAtW = prefixSums(computeWrapCounts(output, w))[n];
            sbVisible = totalAtW > outputArea;
            contentW = sbVisible ? w - 1 : w;
        }
        // 全量折行前缀和：滚动条按「整段内容里的全局显示起点」定位；用窗口内偏移会让短行滑块钉死顶部
        int[] prefix = prefixSums(computeWrapCounts(output, contentW));
        int fullTotal = prefix[n];
        // 原始行按 contentW 折行后取最后 outputArea 段，保证长行内容完整显示而非截断
        List<String> wrapped = new ArrayList<>();
        for (String line : output.visibleLines(outputArea)) {
            wrapped.addAll(wrap(line, contentW));
        }
        int windowFrom = wrapped.size() - outputArea;
        // 全局显示起点（滚动条用）；用窗口内偏移会让短行滑块钉死顶部
        int from = displayFrom(n, outputArea, prefix, output.scrollOffset());
        String[] rows = new String[outputArea];
        for (int i = 0; i < outputArea; i++) {
            int idx = windowFrom + i;
            rows[i] = idx >= 0 && idx < wrapped.size() ? wrapped.get(idx) : "";
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
        drawScrollbar(outputArea, contentW, from, fullTotal);
        if (hasSeparator) {
            moveTo(h - 1, 1);
            write(separatorLine(w));
            write("\033[K");
        }
    }

    /** 输出区可用行数：分隔线上方（h-2），极小窗口无分隔线时用 h-1。 */
    private int outputArea() {
        int h = height();
        return Math.max(1, h >= 3 ? h - 2 : h - 1);
    }

    /**
     * 输出区最右列画滚动条：轨道深灰、滑块浅灰。滑块位置/大小由内容折行总高与当前显示起点决定。
     * 每帧全量重画（内容行写的 \033[K 会清掉本列，不能靠 diff 跳过）。
     */
    private void drawScrollbar(int outputArea, int contentW, int from, int fullTotal) {
        if (fullTotal <= outputArea) {
            return;
        }
        int thumbH = thumbHeight(outputArea, fullTotal);
        int top = thumbTop(outputArea, fullTotal, from);
        for (int i = 0; i < outputArea; i++) {
            moveTo(i + 1, contentW + 1);
            write(i >= top && i < top + thumbH ? SCROLLBAR_THUMB : SCROLLBAR_TRACK);
        }
    }

    /**
     * 滚动条点击/拖动：把鼠标在输出区内的行号 y（1-based）换算成目标滚动位置。
     * 滑块中心对准鼠标行；内容不满一屏时回到底部跟随（无滚动条）。
     */
    public void scrollToMouseY(OutputPane output, int y) {
        int outputArea = outputArea();
        int n = output.lineCount();
        if (n <= 0 || outputArea <= 0) {
            return;
        }
        int[] prefix = prefixSums(computeWrapCounts(output, Math.max(1, width() - 1)));
        int fullTotal = prefix[n];
        if (fullTotal <= outputArea) {
            output.resetScroll();
            return;
        }
        int thumbH = thumbHeight(outputArea, fullTotal);
        int range = Math.max(1, outputArea - thumbH);
        float thumbTop = (y - 1) - (thumbH - 1) / 2.0f; // 滑块中心对准鼠标
        float frac = Math.max(0f, Math.min(1f, thumbTop / range));
        int targetFrom = Math.round(frac * (fullTotal - outputArea));
        output.setScrollOffset(targetScrollOffset(n, outputArea, prefix, targetFrom));
    }

    /** 全部逻辑行按给定宽度折行后的段数（用于算内容总折行高）。 */
    private int[] computeWrapCounts(OutputPane output, int width) {
        List<String> lines = output.lines();
        int[] counts = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            counts[i] = wrap(lines.get(i), width).size();
        }
        return counts;
    }

    private static int[] prefixSums(int[] counts) {
        int[] prefix = new int[counts.length + 1];
        for (int i = 0; i < counts.length; i++) {
            prefix[i + 1] = prefix[i] + counts[i];
        }
        return prefix;
    }

    /** 滑块高度（近似）：视口行数平方 / 内容折行总行数，最小 1。 */
    static int thumbHeight(int outputArea, int fullTotal) {
        return Math.max(1, Math.round((float) outputArea * outputArea / Math.max(1, fullTotal)));
    }

    /**
     * 滚动偏移 s（0 = 底部）时，整段折行内容里第一显示行的下标（0 = 顶部）。
     * 视口显示连续 outputArea 个逻辑行（窗口上界 = n - s）折行后的尾部，起点 = prefix[上界] - outputArea。
     */
    static int displayFrom(int n, int outputArea, int[] prefix, int scrollOffset) {
        int lo = Math.max(0, n - outputArea - scrollOffset);
        return Math.max(0, prefix[Math.min(n, lo + outputArea)] - outputArea);
    }

    /** 滑块顶行（0-based，相对输出区顶部）：当前显示起点 from 占可滚动区间的比例映射到轨道。 */
    static int thumbTop(int outputArea, int fullTotal, int from) {
        int thumbH = thumbHeight(outputArea, fullTotal);
        int range = Math.max(1, outputArea - thumbH);
        float frac = (float) Math.max(0, from) / Math.max(1, fullTotal - outputArea);
        return Math.round(Math.min(1f, frac) * range);
    }

    /**
     * 给定全量折行前缀和期望的显示起点 targetFrom（0 = 顶），反解出应设置的逻辑行 scrollOffset。
     * 视口显示的是连续 outputArea 个逻辑行折行后的尾部，其起点 = prefix[hi] - outputArea，
     * 因此找 prefix[hi] 最接近 targetFrom + outputArea 的 hi，scrollOffset = n - hi。
     */
    static int targetScrollOffset(int n, int outputArea, int[] prefix, int targetFrom) {
        int fullTotal = prefix[n];
        if (fullTotal <= outputArea) {
            return 0;
        }
        targetFrom = Math.max(0, Math.min(fullTotal - outputArea, targetFrom));
        int targetPrefix = targetFrom + outputArea;
        int lo = Math.max(0, Math.min(outputArea, n));
        int hi = n;
        int best = lo;
        int bestErr = Integer.MAX_VALUE;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int err = Math.abs(prefix[mid] - targetPrefix);
            if (err < bestErr) {
                bestErr = err;
                best = mid;
            }
            if (prefix[mid] < targetPrefix) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int maxS = Math.max(0, n - outputArea);
        return Math.max(0, Math.min(maxS, n - best));
    }

    /** 分隔线：一行灰色横线，把输出区与底部输入框分开（类似 Claude Code）。 */
    private static String separatorLine(int w) {
        return "\033[90m" + "─".repeat(Math.max(1, w)) + "\033[0m";
    }

    /** 滚动条轨道：深灰背景空格；滑块：浅灰背景空格（内容不满一屏时不画）。 */
    private static final String SCROLLBAR_TRACK = "\033[48;5;236m \033[0m";
    private static final String SCROLLBAR_THUMB = "\033[48;5;242m \033[0m";

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
