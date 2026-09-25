package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowsConsoleCursorTest {

    @Test
    void convertsBufferCoordinatesToViewportRow() {
        assertEquals(1, WindowsConsoleCursor.viewportRow(40, 40));
        assertEquals(7, WindowsConsoleCursor.viewportRow(46, 40));
    }

    @Test
    void clampsCoordinatesAboveViewport() {
        assertEquals(1, WindowsConsoleCursor.viewportRow(10, 40));
    }
}
