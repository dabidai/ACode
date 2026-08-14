package com.acode.ui;

/**
 * 增量 Markdown 着色渲染器（CommonMark 子集：围栏代码块、标题、加粗、行内代码）。
 * 每次 append 一段增量，随时可 render() 拿到「截至目前的完整着色文本」。
 * 着色基于完整文本每次重新解析，因此分多次 delta 到达不会破坏颜色边界。
 * 输出含 ANSI SGR 序列；普通文本（不含 Markdown 标记）原样返回。
 */
public class MarkdownRenderer {

    static final String RESET = "\033[0m";
    static final String STYLE_HEADING = "\033[1;34m";
    static final String STYLE_BOLD = "\033[1m";
    static final String STYLE_INLINE_CODE = "\033[36m";
    static final String STYLE_CODE_BLOCK = "\033[48;5;236m";

    private final StringBuilder text = new StringBuilder();

    public void append(String delta) {
        if (delta != null && !delta.isEmpty()) {
            text.append(delta);
        }
    }

    /** 返回累积文本的着色结果；应用过样式的行结尾带 RESET，不会向下一段渗色。 */
    public String render() {
        return colorize(text.toString());
    }

    /** 累积原文是否以换行结尾（决定最后一行是否已完整；render() 会丢弃结尾换行信息）。 */
    boolean endsWithNewline() {
        return text.length() > 0 && text.charAt(text.length() - 1) == '\n';
    }

    private static String colorize(String md) {
        if (md.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = md.split("\n", -1);
        // 结尾换行产生的空串是「占位下一行」，不输出，避免多一个空行
        int effective = md.endsWith("\n") ? lines.length - 1 : lines.length;
        boolean inFence = false;
        for (int i = 0; i < effective; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                out.append(STYLE_CODE_BLOCK).append(line).append(RESET);
                inFence = !inFence;
            } else if (inFence) {
                out.append(STYLE_CODE_BLOCK).append(line).append(RESET);
            } else if (isHeading(line)) {
                out.append(STYLE_HEADING).append(line).append(RESET);
            } else {
                out.append(inline(line));
            }
            if (i < effective - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** ATX 标题：1~6 个 # 后跟空白或行尾。 */
    private static boolean isHeading(String line) {
        return line.matches("#{1,6}(\\s.*)?");
    }

    /** 行内：加粗 **、行内代码 `；行内三反引号视为字面量。无任何标记时原样返回。 */
    private static String inline(String line) {
        StringBuilder out = new StringBuilder();
        boolean code = false;
        boolean bold = false;
        boolean changed = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '`' && i + 2 < n && line.charAt(i + 1) == '`' && line.charAt(i + 2) == '`') {
                out.append("```");
                i += 3;
            } else if (c == '`') {
                code = !code;
                changed = true;
                emitStyle(out, code, bold);
                i++;
            } else if (c == '*' && i + 1 < n && line.charAt(i + 1) == '*') {
                bold = !bold;
                changed = true;
                emitStyle(out, code, bold);
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        if (changed) {
            out.append(RESET);
        }
        return out.toString();
    }

    private static void emitStyle(StringBuilder out, boolean code, boolean bold) {
        out.append(RESET);
        if (bold) {
            out.append(STYLE_BOLD);
        }
        if (code) {
            out.append(STYLE_INLINE_CODE);
        }
    }
}
