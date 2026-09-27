package com.acode.ui;

import java.util.ArrayList;
import java.util.List;

/** Content-coordinate anchor: resize and appends do not move a reader to the tail. */
final class TranscriptViewport {
    private record Row(long line, int character, String text) {}
    private List<Row> rows = List.of();
    private boolean following = true;
    private long anchorLine;
    private int anchorCharacter;
    private int top, height = 1;
    private long seenEnd;
    private String seenTail = "";
    private boolean unseen;

    List<String> visible(OutputPane.Snapshot snapshot, int width, int height) {
        this.height = Math.max(1, height);
        List<Row> next = new ArrayList<>();
        if (snapshot.firstLine() > 0) next.add(new Row(snapshot.firstLine() - 1, 0, "更早内容未保留"));
        for (int i = 0; i < snapshot.lines().size(); i++) {
            var styled = org.jline.utils.AttributedString.fromAnsi(snapshot.lines().get(i));
            String plain = ScreenLayout.plain(snapshot.lines().get(i));
            for (var row : ScreenLayout.wrap(plain, width)) {
                String rendered = plain.equals(styled.toString()) && !plain.contains("\t")
                        ? styled.subSequence(row.start(), row.end()).toAnsi() : row.text();
                next.add(new Row(snapshot.firstLine() + i, row.start(), rendered));
            }
        }
        rows = next;
        if (following) top = Math.max(0, rows.size() - this.height);
        else {
            top = 0;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.line() < anchorLine || (row.line() == anchorLine && row.character() <= anchorCharacter)) top = i;
                else break;
            }
            top = Math.min(top, Math.max(0, rows.size() - this.height));
            unseen |= snapshot.firstLine() + snapshot.lines().size() > seenEnd
                    || (!snapshot.lines().isEmpty() && !snapshot.lines().getLast().equals(seenTail));
        }
        if (following) {
            seenEnd = snapshot.firstLine() + snapshot.lines().size(); unseen = false;
            seenTail = snapshot.lines().isEmpty() ? "" : snapshot.lines().getLast();
        }
        return rows.subList(top, Math.min(rows.size(), top + this.height)).stream().map(Row::text).toList();
    }

    void scroll(int delta) {
        top = Math.max(0, Math.min(Math.max(0, rows.size() - height), top + delta));
        following = top >= Math.max(0, rows.size() - height);
        if (!following && !rows.isEmpty()) {
            anchorLine = rows.get(top).line(); anchorCharacter = rows.get(top).character();
        }
        if (following) unseen = false;
    }
    void bottom() { following = true; unseen = false; }
    boolean unseen() { return unseen; }
    int pageSize() { return height; }
}
