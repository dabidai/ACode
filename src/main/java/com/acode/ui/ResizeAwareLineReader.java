package com.acode.ui;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.impl.AbstractWindowsTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import java.util.ArrayList;
import java.util.List;

import java.util.function.Supplier;

/** LineReader that rebuilds ACode's input frame prompt when the terminal is resized. */
final class ResizeAwareLineReader extends LineReaderImpl {

    private volatile Supplier<String> activePromptSupplier;
    private volatile Runnable activeFooterRedraw;
    private volatile boolean resizedDuringLastRead;
    private volatile Supplier<String> frameMode;
    private volatile Supplier<String> frameFooter;
    private volatile Supplier<List<String>> frameHistoryLines;
    private boolean frameNeedsHistoryPaint;
    private List<AttributedString> frameLines = List.of();
    private int historyOffset;
    private int frameCursorRow;
    private int frameCursorColumn;

    ResizeAwareLineReader(Terminal terminal, String appName) {
        super(terminal, appName, null);
    }

    String readLine(Supplier<String> promptSupplier, Runnable footerRedraw) {
        String initialPrompt = promptSupplier.get();
        resizedDuringLastRead = false;
        activePromptSupplier = promptSupplier;
        activeFooterRedraw = footerRedraw;
        try {
            return super.readLine(initialPrompt);
        } finally {
            activePromptSupplier = null;
            activeFooterRedraw = null;
        }
    }

    boolean wasResizedDuringLastRead() {
        return resizedDuringLastRead;
    }

    String readLineFramed(Supplier<String> mode, Supplier<String> footer,
                          Supplier<List<String>> historyLines) {
        Status status = Status.getStatus(terminal, true);
        if (status == null) {
            return readLine(() -> StatusBar.framedInputPrompt(mode.get(), terminal.getWidth()), () -> {});
        }
        resizedDuringLastRead = false;
        frameMode = mode;
        frameFooter = footer;
        frameHistoryLines = historyLines;
        frameNeedsHistoryPaint = status.size() != StatusBar.frameReservedRows(terminal.getHeight());
        historyOffset = 0;
        boolean mouseWasEnabled = isSet(Option.MOUSE);
        // Native Windows mouse capture disables direct console selection.
        // Keep the user's chosen drag-to-copy behavior in CMD, PowerShell,
        // and Windows Terminal. Other terminal providers can use the frame's
        // wheel handler without changing Windows QuickEdit.
        boolean captureMouse = !(terminal instanceof AbstractWindowsTerminal<?>);
        option(Option.MOUSE, captureMouse);
        try {
            return super.readLine("");
        } finally {
            if (historyOffset > 0) {
                paintHistory(0);
            }
            frameMode = null;
            frameFooter = null;
            frameHistoryLines = null;
            frameNeedsHistoryPaint = false;
            frameLines = List.of();
            option(Option.MOUSE, mouseWasEnabled);
        }
    }

    @Override
    public boolean mouse() {
        if (frameMode == null) {
            return super.mouse();
        }
        MouseEvent event = readMouseEvent();
        if (event.getButton() == MouseEvent.Button.WheelUp) {
            paintHistory(historyOffset + 3);
        } else if (event.getButton() == MouseEvent.Button.WheelDown) {
            paintHistory(historyOffset - 3);
        }
        return true;
    }

    private void paintHistory(int requestedOffset) {
        Supplier<List<String>> supplier = frameHistoryLines;
        Status status = Status.getStatus(terminal, false);
        if (supplier == null || status == null) {
            return;
        }
        int height = terminal.getHeight();
        int rows = Math.max(0, height - status.size());
        int width = Math.max(1, terminal.getWidth() - 1);
        if (rows == 0) {
            return;
        }
        List<String> visual = new ArrayList<>();
        for (String line : supplier.get()) {
            visual.addAll(LiveRegionRenderer.wrap(line, width));
        }
        int maxOffset = Math.max(0, visual.size() - rows);
        historyOffset = Math.max(0, Math.min(requestedOffset, maxOffset));
        int end = visual.size() - historyOffset;
        int start = Math.max(0, end - rows);
        List<String> visible = visual.subList(start, end);
        terminal.writer().write("\033[?25l");
        int padding = rows - visible.size();
        for (int row = 0; row < rows; row++) {
            terminal.puts(InfoCmp.Capability.cursor_address, row, 0);
            terminal.puts(InfoCmp.Capability.clr_eol);
            if (row >= padding) {
                terminal.writer().write(visible.get(row - padding));
                terminal.writer().write("\033[0m");
            }
        }
        terminal.puts(InfoCmp.Capability.cursor_address, frameCursorRow, frameCursorColumn);
        terminal.writer().write("\033[?25h");
        terminal.flush();
    }

    @Override
    // WINCH can arrive on a terminal signal thread while readLine is painting.
    // Share the monitor with handleSignal so JLine's Display cache is not mutated concurrently.
    protected synchronized void redisplay(boolean flush) {
        if (frameMode == null) {
            super.redisplay(flush);
            return;
        }
        Status status = Status.getStatus(terminal, true);
        if (status == null) {
            super.redisplay(flush);
            return;
        }
        int width = Math.max(1, terminal.getWidth() - 1);
        int height = terminal.getHeight();
        if (height < 6) {
            status.hide();
            setPrompt("> ");
            super.redisplay(flush);
            return;
        }
        setPrompt("");
        // Keep Status at a constant height while the user edits. Windows CMD
        // corrupts the physical screen when Status changes its scroll region
        // for every newly inserted input line. Reserve at most one third of
        // the screen so the startup banner remains visible above the frame.
        int reservedRows = StatusBar.frameReservedRows(height);
        int inputCapacity = Math.max(1, reservedRows - 4);
        List<String> input = inputRows(buf.toString(), width);
        List<String> beforeCursor = inputRows(buf.upToCursor(), width);
        int cursorInputRow = beforeCursor.size() - 1;
        int cursorColumn = Math.min(width, StatusBar.displayWidth(beforeCursor.get(cursorInputRow)));
        int first = Math.max(0, input.size() - inputCapacity);
        if (cursorInputRow < first) {
            first = cursorInputRow;
        }
        List<String> visibleInput = input.subList(first, Math.min(input.size(), first + inputCapacity));
        List<AttributedString> lines = new ArrayList<>();
        int blankRows = Math.max(0, reservedRows - visibleInput.size() - 4);
        for (int i = 0; i < blankRows; i++) {
            lines.add(new AttributedString(""));
        }
        lines.add(AttributedString.fromAnsi(StatusBar.frameModeLine(frameMode.get(), width)));
        lines.add(AttributedString.fromAnsi(StatusBar.divider(width)));
        for (String line : visibleInput) {
            lines.add(new AttributedString(line));
        }
        lines.add(AttributedString.fromAnsi(StatusBar.divider(width)));
        lines.add(AttributedString.fromAnsi(frameFooter.get()));
        frameLines = List.copyOf(lines);
        // Status saves the current cursor while changing its scroll region.
        // A resize can leave that cursor below the new region, so use the
        // upcoming frame height rather than the old size.
        terminal.puts(InfoCmp.Capability.cursor_address,
                Math.max(0, height - lines.size() - 1), 0);
        status.update(lines, flush);
        int row = height - lines.size() + blankRows + 2 + cursorInputRow - first;
        frameCursorRow = Math.max(0, row);
        frameCursorColumn = cursorColumn;
        terminal.puts(InfoCmp.Capability.cursor_address, frameCursorRow, frameCursorColumn);
        if (flush) {
            terminal.flush();
        }
        if (frameNeedsHistoryPaint) {
            frameNeedsHistoryPaint = false;
            terminal.writer().write("\033[2J\033[H");
            paintFrameAndHistory();
        }
    }

    private static List<String> inputRows(String buffer, int width) {
        List<String> result = new ArrayList<>();
        String[] logical = buffer.split("\\n", -1);
        for (int i = 0; i < logical.length; i++) {
            String prefix = i == 0 ? "> " : "  ";
            String text = prefix + logical[i];
            StringBuilder row = new StringBuilder();
            int columns = 0;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                int cells = Math.max(0, org.jline.utils.WCWidth.wcwidth(cp));
                if (columns + cells > width && row.length() > 0) {
                    result.add(row.toString());
                    row.setLength(0);
                    columns = 0;
                }
                if (cells <= width) {
                    row.appendCodePoint(cp);
                    columns += cells;
                }
                offset += Character.charCount(cp);
            }
            result.add(row.toString());
        }
        return result;
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        Runnable footerRedraw = activeFooterRedraw;
        boolean rebuildFrame = false;
        if (signal == Terminal.Signal.WINCH && frameMode != null) {
            resizedDuringLastRead = true;
            org.jline.terminal.Size next = terminal.getBufferSize();
            rebuildFrame = next.getColumns() != size.getColumns() || next.getRows() != size.getRows();
        }
        if (signal == Terminal.Signal.WINCH && activePromptSupplier != null) {
            resizedDuringLastRead = true;
            try {
                // JLine 3.30 recreates Display on WINCH. Keep all physical cursor
                // movement inside JLine so the rebuilt model matches the terminal.
                setPrompt(activePromptSupplier.get());
            } catch (RuntimeException ignored) {
                // JLine's own WINCH redraw remains the fallback.
            }
        }
        super.handleSignal(signal);
        if (rebuildFrame) {
            historyOffset = 0;
            // JLine has now established the new Status geometry. Repaint the
            // current viewport from OutputPane without appending duplicate
            // conversation lines to the terminal buffer.
            terminal.writer().write("\033[2J\033[H");
            paintFrameAndHistory();
        }
        if (signal == Terminal.Signal.WINCH && footerRedraw != null) {
            try {
                footerRedraw.run();
            } catch (RuntimeException ignored) {
                // The prompt is already usable; the footer will refresh next round.
            }
        }
    }

    private void paintFrameAndHistory() {
        List<AttributedString> lines = frameLines;
        int height = terminal.getHeight();
        int firstRow = Math.max(0, height - lines.size());
        terminal.puts(InfoCmp.Capability.change_scroll_region, 0, Math.max(0, firstRow - 1));
        for (int i = 0; i < lines.size(); i++) {
            terminal.puts(InfoCmp.Capability.cursor_address, firstRow + i, 0);
            terminal.puts(InfoCmp.Capability.clr_eol);
            terminal.writer().write(lines.get(i).toAnsi(terminal));
        }
        paintHistory(0);
    }
}
