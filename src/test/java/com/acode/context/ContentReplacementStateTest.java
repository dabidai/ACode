package com.acode.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T2：ContentReplacementState 决策冻结记账。 */
class ContentReplacementStateTest {

    @Test
    void recordsAndReplaysPreviewById() {
        ContentReplacementState state = new ContentReplacementState();
        assertFalse(state.isReplaced("id-1"));
        assertNull(state.previewFor("id-1"));
        state.record("id-1", "预览A");
        assertTrue(state.isReplaced("id-1"));
        assertEquals("预览A", state.previewFor("id-1"));
        assertEquals(1, state.size());
    }

    @Test
    void resetClearsDecisions() {
        ContentReplacementState state = new ContentReplacementState();
        state.record("id-1", "预览A");
        state.record("id-2", "预览B");
        state.reset();
        assertEquals(0, state.size());
        assertNull(state.previewFor("id-1"));
    }
}
