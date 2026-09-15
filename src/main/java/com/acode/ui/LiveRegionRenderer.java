package com.acode.ui;

import org.jline.utils.AttributedString;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 活跃区渲染器：屏幕底部可原地重绘的文本块（菜单 overlay 等）。
 * 已完成内容由 appendCommitted 追加进原生回滚（一次打印、可划选复制、永不再改）；
 * 重绘用「上移已写行数 → 清到屏尾 → 重写可见后缀」，不触碰上方历史，
 * 只发相对移动（\033[NA）与清屏（\033[J）序列，绝不用绝对定位（\033[r;cH）。
 * 流式回复不再走重绘：由 StreamPrinter 对每个完成的渲染行追加式写屏（无光标操作）。
 */
public class LiveRegionRenderer {

    private final IntSupplier widthSupplier;
    private final IntSupplier heightSupplier;
    /** 上次重绘时的终端尺寸；变化时旧已写行数失效，先归零重锚定（reflow，R3）。 */
    private int lastW = -1;
    private int lastH = -1;
    /** 活跃区已写行数：重绘时需从当前光标位置上移回到活跃区顶部的行数。 */
    private int rowsWritten = 0;

    public LiveRegionRenderer(int width, int height) {
        this(() -> width, () -> height);
    }

    public LiveRegionRenderer(IntSupplier width, IntSupplier height) {
        this.widthSupplier = width;
        this.heightSupplier = height;
    }

    /** 上移行数 = min(已写行数, 屏高-1)；屏高 ≤1 时无法上移返回 0（R3）。 */
    static int upRows(int rowsWritten, int height) {
        if (height <= 1) {
            return 0;
        }
        return Math.min(rowsWritten, height - 1);
    }

    /** 只取末尾可见段：最多 height-1 段（超屏后顶部已滚入回滚不可改，R3）。 */
    static List<String> visibleSegs(List<String> segs, int height) {
        int limit = Math.max(0, height - 1);
        int from = Math.max(0, segs.size() - limit);
        return segs.subList(from, segs.size());
    }

    /**
     * 按终端显示宽度折行：每段显示宽度 ≤ width，折点不切断宽字符与 ANSI 序列；
     * SGR 颜色状态跨段延续（段间不补 RESET）。宽度按 wcwidth（CJK 等宽字符占 2 列）。
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

    /** 活跃区当前已写行数（测试断言用）。 */
    int rowsWritten() {
        return rowsWritten;
    }

    /**
     * 重绘活跃区：上移旧区 → 清到屏尾 → 重写可见后缀，每段以 \r\n 收尾
     * （\r 化解内容宽度恰等于终端宽度时的 pending-wrap 幻影空行，R4）。
     * 终端尺寸变化时旧已写行数失效，先归零重锚定（R3）。
     */
    public void redraw(Writer out, List<String> renderLines) {
        int w = widthSupplier.getAsInt();
        int h = heightSupplier.getAsInt();
        if (w != lastW || h != lastH) {
            rowsWritten = 0;
            lastW = w;
            lastH = h;
        }
        List<String> segs = new ArrayList<>();
        for (String line : renderLines) {
            segs.addAll(wrap(line, w));
        }
        List<String> visible = visibleSegs(segs, h);
        writeSequence(out, upRows(rowsWritten, h), visible);
        rowsWritten = visible.size();
    }

    /** 清空活跃区（菜单取消等）：上移 + 清到屏尾，已写行数归零。 */
    public void clear(Writer out) {
        writeSequence(out, upRows(rowsWritten, heightSupplier.getAsInt()), List.of());
        rowsWritten = 0;
    }

    /**
     * 整屏清空（ED2 + 光标归位）：已提交内容保留在终端滚动缓冲，仅从当前视图移除。
     * 用于「加载会话 / /clear」等重置视图的瞬间；活跃区已写行数归零。
     */
    public void clearScreen(Writer out) {
        writeRaw(out, "\033[2J\033[H");
        rowsWritten = 0;
    }

    /**
     * 底部常驻状态区（JLine {@link org.jline.utils.Status}）：终端滚动区在屏幕底部留出若干行，
     * 由 JLine 的 Display 管理与提示符的让位。等待帧的页脚就画在这里——**放在提示符下方但不归我们
     * 自己管**，这一条是关键区别。
     * <p>早先的失败做法是拿 {@code \033[3A} / {@code \033[J} 手工维护提示符下方那几行：JLine 并不知道
     * 那片区域被占了，多行输入会在上面续画，回车时按自己记的行数擦除，于是留下残行；Ctrl+C 绕过
     * 主循环的擦除路径，残留更明显。交给 {@code Status} 之后这些都不需要了——它的 Display 记着自己的
     * 行数，滚动区内不会把提示符推过来（`Display` 只滚出可见部分），resize 由 LineReader 调
     * {@code status.reset()}，重绘由 {@code status.redraw()} 跟着走。真机缺陷正是这么消掉的。
     *
     * <p><b>创建责任在本方法</b>：JLine 的 {@code LineReaderImpl} 取状态区时一律传 {@code create=false}
     * （只读不建），全库没有一处替我们建；这里必须传 {@code true}，否则拿到的一直是 null，
     * 页脚被静默吞掉（v3 真机复现的正是这个）。滚动区能力在 {@code windows-vtp} 上本就齐备，
     * 不需要往 terminfo 里注入任何东西。
     *
     * @return 状态区实例；终端不支持（非 AbstractTerminal / 缺滚动区能力 / 测试路径）时返回 null，
     *         调用方按「无状态区」降级——只是少一条页脚，功能不受影响
     */
    public static org.jline.utils.Status statusOf(AcodeTerminal tui) {
        if (tui == null) {
            return null;
        }
        return org.jline.utils.Status.getStatus(tui.terminal(), true);
    }

    /**
     * 建立/更新底部状态区。传入 null 或空列表则隐藏状态区，把底部行还给输出。
     * 行数与上次相同即原地重绘（{@code Status} 只在行数变化时动滚动区），所以每轮调用不会抖。
     */
    public static void updateStatus(org.jline.utils.Status status, List<String> lines) {
        if (status == null) {
            return;
        }
        if (lines == null || lines.isEmpty()) {
            status.hide();
            return;
        }
        List<AttributedString> rendered = new ArrayList<>();
        for (String line : lines) {
            rendered.add(AttributedString.fromAnsi(line));
        }
        status.update(rendered);
    }

    /**
     * 追加已提交内容：按 \n 拆行后每行写 行\r\n，原生折行进回滚、可划选复制，
     * 不计已写行数（banner / 输入 / 历史 / 状态行用）。结尾换行不产生多余空行。
     */
    public void appendCommitted(Writer out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String line : body.split("\n", -1)) {
            writeRaw(out, line.replace("\r", "") + "\r\n");
        }
    }

    /** 活跃区内容转为回滚中的历史：已写行数归零、屏幕文本保留（重绘状态重置）。 */
    public void commitRegion() {
        rowsWritten = 0;
    }

    private static void writeSequence(Writer out, int up, List<String> segs) {
        try {
            if (up > 0) {
                out.write("\033[" + up + "A");
            }
            out.write("\033[J");
            for (String seg : segs) {
                out.write(seg);
                out.write("\r\n");
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeRaw(Writer out, String text) {
        try {
            out.write(text);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
