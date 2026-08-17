package com.acode.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineDiffTest {

    private static final int MAX_CHANGE = 300;

    @Test
    void addedLineProducesOnlyPlusLine() {
        List<String> diff = LineDiff.diffLines(List.of("a", "c"), List.of("a", "b", "c"), MAX_CHANGE);
        assertEquals(List.of("+ b"), diff);
    }

    @Test
    void removedLineProducesOnlyMinusLine() {
        List<String> diff = LineDiff.diffLines(List.of("a", "b", "c"), List.of("a", "c"), MAX_CHANGE);
        assertEquals(List.of("- b"), diff);
    }

    @Test
    void changedLineProducesMinusAndPlus() {
        List<String> diff = LineDiff.diffLines(List.of("a", "b", "c"), List.of("a", "x", "c"), MAX_CHANGE);
        assertEquals(List.of("- b", "+ x"), diff);
    }

    @Test
    void identicalContentReturnsEmpty() {
        List<String> diff = LineDiff.diffLines(List.of("a", "b"), List.of("a", "b"), MAX_CHANGE);
        assertEquals(List.of(), diff);
    }

    @Test
    void emptyOldSideProducesOnlyPlusLines() {
        List<String> diff = LineDiff.diffLines(List.of(), List.of("x", "y"), MAX_CHANGE);
        assertEquals(List.of("+ x", "+ y"), diff);
    }

    @Test
    void emptyNewSideProducesOnlyMinusLines() {
        List<String> diff = LineDiff.diffLines(List.of("x", "y"), List.of(), MAX_CHANGE);
        assertEquals(List.of("- x", "- y"), diff);
    }

    @Test
    void changeLargerThanLimitReturnsNull() {
        List<String> old = new ArrayList<>();
        List<String> now = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            old.add("old-" + i);
            now.add("new-" + i);
        }
        assertNull(LineDiff.diffLines(old, now, MAX_CHANGE));
    }

    @Test
    void largeFilesWithSmallChangeStillProduceDiff() {
        int n = 5000;
        List<String> old = new ArrayList<>();
        List<String> now = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            old.add("line " + i);
            now.add("line " + (i == 2500 ? i + 9999 : i));
        }
        List<String> diff = LineDiff.diffLines(old, now, MAX_CHANGE);
        assertNotNull(diff);
        assertEquals(List.of("- line 2500", "+ line 12499"), diff);
    }

    @Test
    void everyDiffLineHasPlusOrMinusPrefix() {
        List<String> diff = LineDiff.diffLines(
                List.of("a", "b", "c", "d"), List.of("a", "x", "c", "e", "d"), MAX_CHANGE);
        assertEquals(List.of("- b", "+ x", "+ e"), diff);
        for (String line : diff) {
            assertTrue(line.startsWith("+ ") || line.startsWith("- "));
        }
    }
}
