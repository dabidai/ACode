package com.acode.ui;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.Wincon;
import com.sun.jna.platform.win32.WinNT;

/** Reads the Windows console cursor without consuming terminal input. */
final class WindowsConsoleCursor {

    private WindowsConsoleCursor() {
    }

    static Integer viewportRow() {
        if (!Platform.isWindows()) {
            return null;
        }
        try {
            WinNT.HANDLE output = Kernel32.INSTANCE.GetStdHandle(Wincon.STD_OUTPUT_HANDLE);
            if (output == null || WinBase.INVALID_HANDLE_VALUE.equals(output)) {
                return null;
            }
            Wincon.CONSOLE_SCREEN_BUFFER_INFO info = new Wincon.CONSOLE_SCREEN_BUFFER_INFO();
            if (!Kernel32.INSTANCE.GetConsoleScreenBufferInfo(output, info)) {
                return null;
            }
            return viewportRow(info.dwCursorPosition.Y, info.srWindow.Top);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static int viewportRow(int bufferRow, int windowTop) {
        return Math.max(1, bufferRow - windowTop + 1);
    }
}
