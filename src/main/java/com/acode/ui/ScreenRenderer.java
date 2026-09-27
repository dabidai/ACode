package com.acode.ui;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jline.terminal.Terminal;

/** The only physical screen writer in full-screen mode. No Status or scroll regions. */
public final class ScreenRenderer extends LiveRegionRenderer implements AutoCloseable {
    private final Terminal terminal;
    private final OutputPane output;
    private final TranscriptViewport viewport = new TranscriptViewport();
    private List<String> previous = List.of(), overlay = List.of();
    private String input = "";
    private int cursor, oldWidth, oldHeight, oldCursorRow = -1, oldCursorColumn = -1;
    private boolean editing, oldEditing, opened, closed;
    private String activity = "";
    private int batch;
    private Supplier<String> mode = () -> "default", footer = () -> "PageUp/PageDown 滚动 · /copy 复制";
    private Terminal.SignalHandler previousResize;

    public ScreenRenderer(Terminal terminal, OutputPane output) {
        super(terminal::getWidth, terminal::getHeight);
        this.terminal = terminal;
        this.output = output;
    }

    public synchronized void open() {
        if (opened || closed) return;
        opened = true;
        terminal.writer().write("\033[?1049h\033[?2004h\033[2J\033[H");
        terminal.puts(org.jline.utils.InfoCmp.Capability.keypad_xmit);
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        previousResize = terminal.handle(Terminal.Signal.WINCH, signal -> refresh());
        refresh();
    }

    public synchronized void labels(Supplier<String> mode, Supplier<String> footer) {
        this.mode = mode; this.footer = footer;
    }
    public synchronized void edit(String input, int cursor) {
        this.input = input; this.cursor = cursor; editing = true; refresh();
    }
    public synchronized void submitted() {
        input = ""; cursor = 0; editing = false; refresh();
    }
    public synchronized void scroll(int rows) { viewport.scroll(rows); refresh(); }
    public synchronized void page(int direction) { scroll(direction * viewport.pageSize()); }
    public synchronized void bottom() { viewport.bottom(); refresh(); }
    public synchronized void beginUpdate() { batch++; }
    public synchronized void endUpdate() { if (batch > 0) batch--; refresh(); }
    public synchronized void notice(String text) { output.append(text); refresh(); }
    public synchronized void overlay(List<String> lines) { overlay = List.copyOf(lines); refresh(); }
    public synchronized void activity(String text) { activity = text; refresh(); }

    /** Called only by the exchange loop, never concurrently with the line editor or a menu. */
    public boolean pollNavigation() throws java.io.IOException {
        var reader = terminal.reader();
        int next = reader.peek(10);
        if (next == 3) { reader.read(); return true; }
        if (next != 27) {
            // Busy mode has no draft editor: discard unsupported keys so cancellation stays reachable.
            if (next >= 0) reader.read();
            return false;
        }
        reader.read();
        if (reader.read(30) != '[') return false;
        StringBuilder sequence = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            int c = reader.read(30);
            if (c < 0) break;
            sequence.append((char) c);
            if (c >= 0x40 && c <= 0x7e) break;
        }
        String key = sequence.toString();
        switch (key) {
            case "5~" -> page(-1);
            case "6~" -> page(1);
            case "1;5F", "4;5~" -> bottom();
            default -> {
                if (key.startsWith("<64;")) scroll(-3);
                if (key.startsWith("<65;")) scroll(3);
            }
        }
        return false;
    }

    public synchronized void refresh() {
        if (!opened || closed || batch > 0) return;
        int columns = Math.max(1, terminal.getWidth()), height = Math.max(1, terminal.getHeight());
        int width = Math.max(1, columns - 1);
        var editor = ScreenLayout.editor(input, cursor, width);
        boolean compact = height < 6 || columns < 20;
        int capacity = compact ? 1 : Math.min(Math.min(8, Math.max(1, height / 3)), height - 5);
        int inputHeight = Math.min(capacity, editor.rows().size());
        int frameHeight = compact ? 1 : inputHeight + 4;
        int transcriptHeight = Math.max(0, height - frameHeight);
        int firstInput = Math.max(0, Math.min(editor.cursorRow(), editor.rows().size() - inputHeight));
        List<String> frame = new ArrayList<>(java.util.Collections.nCopies(height, ""));
        List<String> visible = viewport.visible(output.snapshot(), width, Math.max(1, transcriptHeight));
        for (int i = 0; i < Math.min(transcriptHeight, visible.size()); i++) frame.set(i, visible.get(i));
        if (!overlay.isEmpty() && transcriptHeight > 0) {
            List<String> menu = new ArrayList<>();
            int selected = 0;
            for (String line : overlay) {
                if (line.contains("\033[7m")) selected = menu.size();
                for (var row : ScreenLayout.wrap(ScreenLayout.plain(line), width)) menu.add(row.text());
            }
            int from = Math.max(0, selected - transcriptHeight + 1);
            for (int i = 0; i < transcriptHeight; i++) frame.set(i, from + i < menu.size() ? menu.get(from + i) : "");
        }
        int editRow = transcriptHeight;
        if (!compact) {
            frame.set(editRow++, ScreenLayout.clip("[" + mode.get() + "]" + (viewport.unseen() ? " · 有新内容 · Ctrl+End 返回" : activity.isEmpty() ? "" : " · " + activity), width));
            frame.set(editRow++, AnsiPalette.DIM + "─".repeat(width) + AnsiPalette.RESET);
        }
        for (int i = 0; i < inputHeight; i++) frame.set(editRow + i, editor.rows().get(firstInput + i));
        if (!compact) {
            frame.set(editRow + inputHeight, AnsiPalette.DIM + "─".repeat(width) + AnsiPalette.RESET);
            frame.set(editRow + inputHeight + 1, ScreenLayout.clip(footer.get(), width));
        }
        int cursorRow = Math.min(height - 1, editRow + editor.cursorRow() - firstInput);
        int cursorCol = Math.min(columns - 1, editor.cursorColumn());
        boolean resized = oldWidth != columns || oldHeight != height;
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < height; i++) {
            if (resized || i >= previous.size() || !frame.get(i).equals(previous.get(i))) {
                bytes.append("\033[").append(i + 1).append(";1H\033[0m\033[2K").append(frame.get(i));
            }
        }
        if (!bytes.isEmpty() || oldCursorRow != cursorRow || oldCursorColumn != cursorCol || editing != oldEditing) {
            terminal.writer().write("\033[?2026h\033[?25l" + bytes + "\033[" + (cursorRow + 1) + ";" + (cursorCol + 1)
                    + "H" + (editing ? "\033[?25h" : "") + "\033[?2026l");
            terminal.flush();
        }
        previous = List.copyOf(frame); oldWidth = columns; oldHeight = height;
        oldCursorRow = cursorRow; oldCursorColumn = cursorCol;
        oldEditing = editing;
    }

    @Override public void appendCommitted(Writer out, String text) { refresh(); }
    @Override public void notice(Writer out, String text) { notice(text); }
    @Override public void redraw(Writer out, List<String> lines) { overlay(lines); }
    @Override public void clear(Writer out) { overlay(List.of()); }
    @Override public synchronized void clearScreen(Writer out) { overlay = List.of(); viewport.bottom(); previous = List.of(); }
    @Override public void commitRegion() { }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!opened) return;
        try {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.puts(org.jline.utils.InfoCmp.Capability.keypad_local);
        }
        finally {
            terminal.writer().write("\033[?1000l\033[?1002l\033[?1003l\033[?1006l\033[?2004l\033[0m\033[r\033[?25h\033[?1049l");
            terminal.flush();
            if (previousResize != null) terminal.handle(Terminal.Signal.WINCH, previousResize);
        }
    }
}
