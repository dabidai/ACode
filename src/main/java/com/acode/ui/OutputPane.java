package com.acode.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出区内容模型：逐行保存已提交内容，超出上限丢弃最早行，防止内存无限增长。
 * 只管理文本内容，不涉及终端绘制（绘制由 LiveRegionRenderer 负责）。
 */
public class OutputPane {

    private static final int DEFAULT_MAX_LINES = 2000;

    private final int maxLines;
    private final List<String> lines = new ArrayList<>();

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

    /**
     * 全部行的快照副本（不可修改）。流式输出时工作线程会并发 append，快照保证读取方看到稳定的行集。
     */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}
