package com.acode.ui;

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
 *
 * 尾行协议：{@link #setTailRow} 登记一行「常驻末尾」的文本（流式计时器用）。
 * 不变量是尾行恒为屏幕最后一行已提交内容、光标在其下一行第 0 列，故原地更新它的偏移
 * 恒为 1——不需要跨调用计数，也不可能因回答超过一屏、尾行滚进回滚而失效。
 * 代价是 {@link #appendCommitted} 必须先抬起尾行（\033[1A\r\033[2K）、写完新内容再贴回底部。
 */
public class LiveRegionRenderer {

    /** 等待帧中模式提示行到光标（提示符行）的行数：模式提示行 + 分隔线。 */
    public static final int WAITING_FRAME_ROWS = 2;

    private final IntSupplier widthSupplier;
    private final IntSupplier heightSupplier;
    /** 上次重绘时的终端尺寸；变化时旧已写行数失效，先归零重锚定（reflow，R3）。 */
    private int lastW = -1;
    private int lastH = -1;
    /** 活跃区已写行数：重绘时需从当前光标位置上移回到活跃区顶部的行数。 */
    private int rowsWritten = 0;
    /** 模式提示行到光标的行数（等待态恒为 WAITING_FRAME_ROWS），用于 Shift+Tab 原地上移定位模式提示行。 */
    private int linesSinceFrame = 0;
    /** 常驻末尾的尾行文本；null 表示当前没有尾行。 */
    private String tailRow;

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

    /** 模式提示行到光标的行数（Shift+Tab 上移定位用）。 */
    public int linesSinceFrame() {
        return linesSinceFrame;
    }

    /**
     * 是否正停在一个未被追加过的等待帧上。renderWaitingFrame 后为真；此后任何 appendCommitted
     * 都会让 linesSinceFrame 增长、把模式提示行推远（甚至推进回滚），原地重写就会改坏屏幕顶行。
     * 这是 Shift+Tab 原地重写模式提示行的唯一合法前提。
     */
    public boolean atWaitingFrame() {
        return linesSinceFrame == WAITING_FRAME_ROWS;
    }

    /** 重置 linesSinceFrame 计数器。 */
    public void resetLinesSinceFrame() {
        linesSinceFrame = 0;
    }

    /** 设置模式提示行到光标的行数（等待帧渲染后设为 2）。 */
    public void setLinesSinceFrame(int value) {
        linesSinceFrame = value;
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
     * 用于「加载会话 / /clear」等重置视图的瞬间；活跃区已写行数与尾行归零。
     */
    public void clearScreen(Writer out) {
        writeRaw(out, "\033[2J\033[H");
        rowsWritten = 0;
        linesSinceFrame = 0;
        tailRow = null;
    }

    /**
     * 擦掉光标以下的所有残留并重锚定状态：等待帧把页脚画在提示符行**下方**，而 JLine 的
     * ERASE_LINE_ON_FINISH 只擦提示符行本身，于是回车后页脚分隔线与模型信息行仍留在屏上；
     * appendCommitted 写 行\r\n 时不先清行，短文本盖不住长页脚的尾巴。交换/命令输出开始前
     * 必须先擦干净，光标停在原处（\033[J 不移动光标）。
     */
    public void clearBelowCursor(Writer out) {
        writeRaw(out, "\033[J");
        rowsWritten = 0;
        linesSinceFrame = 0;
        tailRow = null;
    }

    /**
     * 登记常驻末尾的尾行（流式计时器用）：已有尾行则先抬起（上移 1 行、回列 0、清行）再重写，
     * 净位移为 0；从无到有则直接写一行、光标下移 1。两种情况下尾行都仍是屏幕最后一行已提交内容。
     */
    public void setTailRow(Writer out, String text) {
        if (tailRow != null) {
            writeRaw(out, "\033[1A\r\033[2K");
        } else {
            linesSinceFrame++;
        }
        writeRaw(out, text + "\r\n");
        tailRow = text;
    }

    /**
     * 撤销尾行：抬起并清空该行，光标停在这条空白行上，后续 appendCommitted 正好从这里接着写，
     * 不留多余空行。
     */
    public void clearTailRow(Writer out) {
        if (tailRow == null) {
            return;
        }
        writeRaw(out, "\033[1A\r\033[2K");
        tailRow = null;
        linesSinceFrame--;
    }

    /**
     * 追加已提交内容：按 \n 拆行后每行写 行\r\n，原生折行进回滚、可划选复制，
     * 不计已写行数（banner / 输入 / 历史 / 状态行用）。结尾换行不产生多余空行。
     * 存在尾行时先抬起、写完新内容再贴回底部，保证尾行恒为最后一行；光标净位移仍为新增行数。
     */
    public void appendCommitted(Writer out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (tailRow != null) {
            writeRaw(out, "\033[1A\r\033[2K");
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String line : body.split("\n", -1)) {
            writeRaw(out, line.replace("\r", "") + "\r\n");
            linesSinceFrame++;
        }
        if (tailRow != null) {
            writeRaw(out, tailRow + "\r\n");
        }
    }

    /**
     * 活跃区内容转为回滚中的历史：已写行数归零、屏幕文本保留（重绘状态重置）。
     * 不写屏，故也不动尾行——尾行的清除需要 Writer，由 clearTailRow / clearBelowCursor 负责。
     */
    public void commitRegion() {
        rowsWritten = 0;
        linesSinceFrame = 0;
    }

    /**
     * 渲染等待输入帧：模式提示行 + 分隔线（提示符上方）→ 预留提示符空行 →
     * 页脚分隔线 + 模型信息行（提示符下方）→ 光标上移 3 行回到预留的提示符行，
     * 随后由 JLine 在此绘制 {@code >*} 提示符。linesSinceFrame 设为 WAITING_FRAME_ROWS。
     * 相对上移对「页脚落在屏幕末行触发滚动」是安全的：内容与光标同步位移，偏移恒为 3。
     * 前提：调用时不存在尾行（交换与命令输出都以 clearTailRow / clearBelowCursor 收尾或开头）。
     */
    public void renderWaitingFrame(Writer out, String modeHint, String divider,
                                   String footerDivider, String footerModel) {
        appendCommitted(out, modeHint);
        appendCommitted(out, divider);
        writeRaw(out, "\r\n");              // 预留提示符行（稍后由 JLine 的 >* 覆盖）
        appendCommitted(out, footerDivider);
        appendCommitted(out, footerModel);
        writeRaw(out, "\033[3A");           // 光标上移 3 行回到预留的提示符行
        linesSinceFrame = WAITING_FRAME_ROWS;
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
