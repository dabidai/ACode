package com.acode.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.AttributedString;
import org.jline.utils.WCWidth;

/** Pure cell geometry shared by transcript, menus and the editor. */
public final class ScreenLayout {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    public record TextRow(String text, int start, int end) {}
    public record Editor(List<String> rows, int cursorRow, int cursorColumn) {}

    private ScreenLayout() {}

    public static String plain(String text) {
        return AttributedString.fromAnsi(text).toString().replaceAll("[\\x00-\\x08\\x0B-\\x1F\\x7F]", "");
    }

    static int cells(String cluster) {
        int width = cluster.codePoints().map(c -> Math.max(0, WCWidth.wcwidth(c))).sum();
        if (cluster.contains("\u200d") || cluster.contains("\ufe0f")
                || cluster.codePoints().anyMatch(c -> c >= 0x1f1e6 && c <= 0x1f1ff)) return 2;
        return width;
    }

    static List<TextRow> wrap(String text, int width) {
        width = Math.max(1, width);
        List<TextRow> result = new ArrayList<>();
        var matcher = GRAPHEME.matcher(text.replace('\t', ' '));
        StringBuilder row = new StringBuilder();
        int columns = 0, start = 0;
        while (matcher.find()) {
            String cluster = matcher.group();
            if (cluster.equals("\n") || cluster.equals("\r\n")) {
                result.add(new TextRow(row.toString(), start, matcher.start()));
                start = matcher.end(); row.setLength(0); columns = 0; continue;
            }
            int cells = cells(cluster);
            if (columns + cells > width && !row.isEmpty()) {
                result.add(new TextRow(row.toString(), start, matcher.start()));
                start = matcher.start();
                row.setLength(0);
                columns = 0;
            }
            if (cells <= width) { row.append(cluster); columns += cells; }
        }
        result.add(new TextRow(row.toString(), start, text.length()));
        return result;
    }

    static String clip(String text, int width) { return wrap(plain(text), width).getFirst().text(); }

    public static Editor editor(String text, int cursor, int width) {
        text = text.replaceAll("[\\x00-\\x08\\x0B-\\x1F\\x7F]", " ");
        List<String> rows = new ArrayList<>();
        int offset = 0, cursorRow = 0, cursorColumn = 0;
        String[] logical = text.split("\n", -1);
        for (int i = 0; i < logical.length; i++) {
            String prefix = i == 0 ? "> " : "  ";
            List<TextRow> wrapped = wrap(prefix + logical[i], width);
            if (cursor >= offset && cursor <= offset + logical[i].length()) {
                int position = cursor - offset + prefix.length();
                for (int r = 0; r < wrapped.size(); r++) {
                    TextRow line = wrapped.get(r);
                    if (position >= line.start() && (position < line.end() || r == wrapped.size() - 1)) {
                        cursorRow = rows.size() + r;
                        String left = (prefix + logical[i]).substring(line.start(), Math.min(position, line.end()));
                        var m = GRAPHEME.matcher(left);
                        cursorColumn = 0;
                        while (m.find()) cursorColumn += cells(m.group());
                        break;
                    }
                }
            }
            wrapped.forEach(r -> rows.add(r.text()));
            offset += logical[i].length() + 1;
        }
        return new Editor(List.copyOf(rows), cursorRow, Math.min(width, cursorColumn));
    }
}
