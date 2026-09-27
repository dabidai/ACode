package com.acode.ui;

import java.io.Writer;
import java.util.List;

/** Redirected output must not receive any cursor, color or alternate-screen commands. */
final class PlainTextRenderer extends LiveRegionRenderer {
    PlainTextRenderer() { super(80, 24); }
    @Override public void appendCommitted(Writer out, String text) { super.appendCommitted(out, ScreenLayout.plain(text)); }
    @Override public void redraw(Writer out, List<String> lines) { for (String line : lines) appendCommitted(out, line); }
    @Override public void clear(Writer out) { }
    @Override public void clearScreen(Writer out) { }
}
