package com.acode.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultTest {

    @Test
    void withDisplayKeepsContentAndSetsDisplay() {
        ToolResult r = ToolResult.success("x").withDisplay("d");
        assertEquals("x", r.content());
        assertEquals("d", r.display());
        assertTrue(r.isSuccess());
    }

    @Test
    void displayDefaultsToNull() {
        assertNull(ToolResult.success("x").display());
        assertNull(ToolResult.failure("e").display());
    }

    @Test
    void withDisplayReturnsIndependentInstances() {
        ToolResult base = ToolResult.success("x");
        ToolResult a = base.withDisplay("a");
        ToolResult b = base.withDisplay("b");
        assertNull(base.display());
        assertEquals("a", a.display());
        assertEquals("b", b.display());
        assertEquals("x", a.content());
        assertEquals("x", b.content());
    }

    @Test
    void failureResultCanCarryDisplay() {
        ToolResult r = ToolResult.failure("e").withDisplay("d");
        assertTrue(r.isError());
        assertEquals("e", r.content());
        assertEquals("d", r.display());
    }
}
