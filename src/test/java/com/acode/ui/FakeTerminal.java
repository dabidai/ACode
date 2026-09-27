package com.acode.ui;

import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.Writer;

/**
 * 忠实终端模拟：解析活跃区渲染器发出的转义流（光标上移 \033[NA、清到屏尾 \033[J、
 * 清行 \033[K、\r、\n、SGR 着色），并按真实终端规则渲染：字符占 wcwidth 列、到右缘
 * 自动换行、写到底部自动滚动、光标上移封顶到第 0 行。用于复现「代码假定宽度 ≠ 终端
 * 实际宽度」时活跃区重绘的错位乱码，单测 StringWriter 复现不了。
 */
public class FakeTerminal {

    private final int termWidth;
    private final int height;
    /** 屏幕缓冲：每行一个 StringBuilder（可能留旧内容，模拟真实终端行内容）。 */
    private final StringBuilder[] rows;
    private int row = 0;
    private int col = 0;
    private int savedRow, savedCol, scrollTop, scrollBottom;
    private String[] primary;
    private final StringBuilder debugLog = new StringBuilder();

    public FakeTerminal(int termWidth, int height) {
        this.termWidth = termWidth;
        this.height = height;
        this.scrollBottom = height - 1;
        this.rows = new StringBuilder[height];
        for (int i = 0; i < height; i++) {
            rows[i] = new StringBuilder();
        }
    }

    /** 记录原始输入流（调试用）。 */
    public String debugLog() {
        return debugLog.toString();
    }

    /** 模拟终端 writer：渲染器把转义流写到这里。 */
    public Writer writer() {
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                String s = new String(cbuf, off, len);
                debugLog.append(s);
                FakeTerminal.this.write(s);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    /** 逐字符解析转义流并渲染。宽字符（CJK）占 2 列，第二格用 NUL 标记为延续格。 */
    void write(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\033') {
                if (i + 1 < n && s.charAt(i + 1) == '[') {
                    int j = i + 2;
                    StringBuilder params = new StringBuilder();
                    while (j < n && !(s.charAt(j) >= '@' && s.charAt(j) <= '~')) {
                        params.append(s.charAt(j));
                        j++;
                    }
                    char finalByte = j < n ? s.charAt(j) : 0;
                    j++;
                    String paramText = params.toString();
                    String[] coordinates = paramText.split(";", -1);
                    if (finalByte == 'H' || finalByte == 'f') {
                        row = Math.max(0, Math.min(height - 1, parseInt(coordinates[0], 1) - 1));
                        col = Math.max(0, Math.min(termWidth - 1, coordinates.length > 1 ? parseInt(coordinates[1], 1) - 1 : 0));
                    } else if (finalByte == 'r' && !paramText.startsWith("?")) {
                        scrollTop = Math.max(0, parseInt(coordinates[0], 1) - 1);
                        scrollBottom = Math.min(height - 1, coordinates.length > 1 ? parseInt(coordinates[1], height) - 1 : height - 1);
                        row = col = 0;
                    } else if (finalByte == 's') {
                        savedRow = row; savedCol = col;
                    } else if (finalByte == 'u') {
                        row = savedRow; col = savedCol;
                    } else if (paramText.equals("?1049") && finalByte == 'h') {
                        primary = new String[height];
                        for (int r = 0; r < height; r++) { primary[r] = rows[r].toString(); rows[r].setLength(0); }
                        savedRow = row; savedCol = col; row = col = 0;
                    } else if (paramText.equals("?1049") && finalByte == 'l' && primary != null) {
                        for (int r = 0; r < height; r++) rows[r] = new StringBuilder(primary[r]);
                        row = savedRow; col = savedCol; primary = null;
                    } else if (finalByte == 'A') {
                        int up = parseInt(params.toString(), 1);
                        row = Math.max(0, row - up);
                    } else if (finalByte == 'B') {
                        int down = parseInt(params.toString(), 1);
                        row = Math.min(height - 1, row + down);
                    } else if (finalByte == 'J') {
                        int mode = parseInt(params.toString(), 0);
                        if (mode == 0 || mode == 2) {
                            for (int r = (mode == 0 ? row : 0); r < height; r++) {
                                rows[r].setLength(0);
                            }
                        }
                    } else if (finalByte == 'K') {
                        if (parseInt(paramText, 0) == 2) rows[row].setLength(0);
                        else rows[row].setLength(Math.min(col, rows[row].length()));
                    }
                    // 其余（SGR 着色等）无布局影响，直接跳过
                    i = j;
                } else {
                    i += 2; // 裸 ESC + 下一字节，跳过
                }
            } else if (c == '\r') {
                col = 0;
                i++;
            } else if (c == '\n') {
                row++;
                if (row > scrollBottom) {
                    scrollUp(1);
                    row = scrollBottom;
                }
                i++;
            } else {
                int cp = s.codePointAt(i);
                int w = Math.max(1, WCWidth.wcwidth(cp));
                int cnt = Character.charCount(cp);
                if (col + w > termWidth) {
                    // 到右缘自动换行
                    row++;
                    if (row >= height) {
                        scrollUp(1);
                        row = height - 1;
                    }
                    col = 0;
                }
                ensureRowLength(row, col + w);
                StringBuilder sb = rows[row];
                sb.setCharAt(col, (char) cp);
                for (int k = 1; k < w; k++) {
                    sb.setCharAt(col + k, '\0'); // 宽字符延续格
                }
                col += w;
                i += cnt;
            }
        }
    }

    /** 保证行缓冲区至少能容纳到 col 之后；不足处用空格补齐（旧内容保持，模拟真实终端残留）。 */
    private void ensureRowLength(int r, int upto) {
        StringBuilder sb = rows[r];
        while (sb.length() < upto) {
            sb.append(' ');
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void scrollUp(int lines) {
        for (int r = scrollTop; r + lines <= scrollBottom; r++) {
            rows[r] = rows[r + lines];
        }
        for (int r = Math.max(scrollTop, scrollBottom + 1 - lines); r <= scrollBottom; r++) {
            rows[r] = new StringBuilder();
        }
    }

    public int height() {
        return height;
    }

    /** 全屏文本拼接（每行 rawLine 用 \n 连接），供断言搜索子串。 */
    public String screenText() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < height; r++) {
            sb.append(readable(rows[r])).append('\n');
        }
        return sb.toString();
    }

    /** 第 r 行的可见文本（去尾空白与宽字符延续格）。 */
    public String line(int r) {
        return readable(rows[r]).stripTrailing();
    }

    /** 全屏行内容（含空白与延续格），供断言逐行检查。 */
    public String rawLine(int r) {
        return rows[r].toString();
    }

    /** NUL 延续格不可见，直接剔除。 */
    private static String readable(StringBuilder sb) {
        StringBuilder out = new StringBuilder(sb.length());
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c != '\0') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 第 r 行是否包含两段互不相连的非空白片段（真实重绘不会产生，宽度错位时出现）。
     * 宽字符延续格（NUL）视为所属片段的延续，只有真正的空格分隔两段。
     */
    public boolean hasDisjointRuns(int r) {
        String line = rows[r].toString();
        boolean seenRun = false;
        boolean inRun = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                if (inRun) {
                    inRun = false;
                    seenRun = true;
                }
            } else {
                if (!inRun) {
                    inRun = true;
                    if (seenRun) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
