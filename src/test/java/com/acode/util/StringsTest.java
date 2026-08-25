package com.acode.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringsTest {

    @Test
    void emptyStringReturnsEmptyList() {
        assertEquals(List.of(), Strings.splitLines(""));
    }

    @Test
    void splitsSimpleLines() {
        assertEquals(List.of("a", "b"), Strings.splitLines("a\nb"));
    }

    @Test
    void trailingNewlineProducesNoEmptySegment() {
        assertEquals(List.of("a", "b"), Strings.splitLines("a\nb\n"));
    }

    @Test
    void keepsBlankMiddleLines() {
        assertEquals(List.of("a", "", "b"), Strings.splitLines("a\n\nb"));
    }

    @Test
    void stripsCarriageReturns() {
        assertEquals(List.of("a", "b"), Strings.splitLines("a\r\nb\r\n"));
    }

    @Test
    void bareNewlineYieldsSingleEmptySegment() {
        assertEquals(List.of(""), Strings.splitLines("\n"));
    }
}
