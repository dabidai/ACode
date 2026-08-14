package com.acode.ui;

import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 活跃区渲染器：屏幕底部可原地重绘的文本块（流式回复、工具卡片、状态行）。
 * 已完成内容由 appendCommitted 追加进原生回滚（一次打印、可划选复制、永不再改）；
 * 活跃区每次重绘用「上移已写行数 → 清到屏尾 → 重写可见后缀」，不触碰上方历史。
 * 重绘只发相对移动（\033[NA）与清屏（\033[J）序列，绝不用绝对定位（\033[r;cH）。
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
