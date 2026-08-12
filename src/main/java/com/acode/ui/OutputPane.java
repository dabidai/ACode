package com.acode.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 输出区内容模型：逐行保存，超出上限丢弃最早行，防止内存无限增长。
 * 只管理文本内容，不涉及终端绘制（绘制由 AcodeTerminal 负责）。
 */
public class OutputPane {

    private static final int DEFAULT_MAX_LINES = 2000;

    private final int maxLines;
    private final List<String> lines = new ArrayList<>();
    /** 滚动回看偏移：0 = 跟随底部；>0 = 向上回看的历史行数（会被视口高度 clamp）。 */
    private int scrollOffset = 0;

    public OutputPane() {
        this(DEFAULT_MAX_LINES);
    }

    public OutputPane(int maxLines) {
        this.maxLines = maxLines;
    }

    /**
     * 追加文本并按 \n 拆行；结尾换行不产生多余空行；中间的空白行保留；
     * \r（CRLF）被剥离。
     */
    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String line : body.split("\n", -1)) {
            appendLine(line.replace("\r", ""));
        }
    }

    public synchronized void appendLine(String line) {
        if (line == null) {
            return;
        }
        lines.add(line);
        while (lines.size() > maxLines) {
            lines.remove(0);
        }
    }

    public synchronized void clear() {
        lines.clear();
    }

    /** 从末尾移除 count 行（超出已有行数则全部移除）；count ≤ 0 无操作。 */
    public synchronized void removeLast(int count) {
        if (count <= 0) {
            return;
        }
        int n = Math.min(count, lines.size());
        for (int i = 0; i < n; i++) {
            lines.remove(lines.size() - 1);
        }
    }

    public synchronized int lineCount() {
        return lines.size();
    }

    /** 全部行（不可修改）。 */
    public synchronized List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** 可见窗口：按滚动偏移取窗口（0 = 底部跟随，>0 = 向上回看）；高度超过内容时偏移被 clamp 到顶部。 */
    public synchronized List<String> visibleLines(int height) {
        if (height <= 0 || lines.isEmpty()) {
            return List.of();
        }
        int maxOffset = Math.max(0, lines.size() - height);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        int from = Math.max(0, lines.size() - height - scrollOffset);
        return List.copyOf(lines.subList(from, Math.min(lines.size(), from + height)));
    }

    /** 向上滚动 n 行（回看更早历史）；偏移会被下次 visibleLines 按视口高度 clamp 到顶部。 */
    public synchronized void scrollUp(int n) {
        if (n > 0) {
            scrollOffset += n;
        }
    }

    /** 向下滚动 n 行（回到更晚内容）；最小到 0（跟随底部）。 */
    public synchronized void scrollDown(int n) {
        if (n > 0) {
            scrollOffset = Math.max(0, scrollOffset - n);
        }
    }

    /** 统一滚动：delta > 0 向上回看，delta < 0 向下回底部；0 无操作。滚轮传小步长，翻页传一屏。 */
    public synchronized void scrollBy(int delta) {
        if (delta > 0) {
            scrollUp(delta);
        } else if (delta < 0) {
            scrollDown(-delta);
        }
    }

    /** 回到底部跟随模式；提交新消息/加载会话/清屏后调用，确保视口回到最新内容。 */
    public synchronized void resetScroll() {
        scrollOffset = 0;
    }

    /** 直接设置滚动偏移（0 = 底部跟随；>0 = 向上回看）。上界由 visibleLines 按视口高度 clamp。 */
    public synchronized void setScrollOffset(int offset) {
        scrollOffset = Math.max(0, offset);
    }

    /** 当前滚动偏移（0 = 底部跟随；>0 = 向上回看的历史行数）。 */
    public synchronized int scrollOffset() {
        return scrollOffset;
    }
}
