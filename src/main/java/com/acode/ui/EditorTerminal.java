package com.acode.ui;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.jline.terminal.Terminal;

/** JLine owns editing and input, while ScreenRenderer exclusively owns physical output. */
final class EditorTerminal {
    private EditorTerminal() {}
    static Terminal wrap(Terminal terminal) {
        PrintWriter sink = new PrintWriter(OutputStream.nullOutputStream());
        return (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(), new Class<?>[]{Terminal.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "writer" -> sink;
                        case "output" -> OutputStream.nullOutputStream();
                        case "puts", "trackMouse", "trackFocus" -> true;
                        case "flush", "close" -> null;
                        case "getCursorPosition" -> null;
                        default -> {
                            try { yield method.invoke(terminal, args); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        }
                    };
                });
    }
}
