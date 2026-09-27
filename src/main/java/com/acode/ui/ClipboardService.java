package com.acode.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/** Clipboard boundary is injectable; automated tests never change the user's clipboard. */
public final class ClipboardService {
    private final Consumer<String> write;
    public ClipboardService() { this(text -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null)); }
    public ClipboardService(Consumer<String> write) { this.write = write; }
    public boolean copy(String text) {
        try { write.accept(text); return true; }
        catch (RuntimeException e) { return false; }
    }
}
