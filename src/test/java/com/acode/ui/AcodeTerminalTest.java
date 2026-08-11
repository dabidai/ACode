package com.acode.ui;

import org.jline.utils.WCWidth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcodeTerminalTest {

    /** 计算字符串显示宽度（忽略 ANSI 转义序列，包括 \033[...m 整段）。 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033') {
                i++;
                if (i < s.length() && s.charAt(i) == '[') {
                    i++;
                    while (i < s.length() && !(s.charAt(i) >= 0x40 && s.charAt(i) <= 0x7e)) {
                        i++;
                    }
                    i++;
                } else {
                    i++;
                }
            } else {
                int cp = s.codePointAt(i);
                w += Math.max(0, WCWidth.wcwidth(cp));
                i += Character.charCount(cp);
            }
        }
        return w;
    }

    @Test
    void truncateKeepsDisplayWidthWithinLimit() {
        String line = "Redis 是互联网后端极其重要的基础设施，它不仅是一个缓存";
        String t = AcodeTerminal.truncateToWidth(line, 20);
        assertTrue(displayWidth(t) <= 20);
        assertTrue(t.startsWith("Redis "));
    }

    @Test
    void truncateDoesNotCutWholeWidthCharacter() {
        // 宽度 1 的余量放不下 2 列的中文字符 → 该字符整体截掉，不返回半个
        String t = AcodeTerminal.truncateToWidth("a中文", 2);
        assertEquals("a\033[0m", t);
    }

    @Test
    void truncateReturnsLineUnchangedWhenFits() {
        String line = "abc";
        assertEquals(line, AcodeTerminal.truncateToWidth(line, 10));
        assertEquals(line, AcodeTerminal.truncateToWidth(line, 3));
    }

    @Test
    void truncateIgnoresAnsiEscapeWidth() {
        String t = AcodeTerminal.truncateToWidth("x[31m中文", 1);
        assertEquals("x[0m", t);
    }

    @Test
    void truncateAppendsResetWhenCutMidColor() {
        String t = AcodeTerminal.truncateToWidth("[31m很长的一行内容", 5);
        assertTrue(t.endsWith("[0m"));
    }

    @Test
    void truncateTracksCjkAsTwoColumns() {
        // 4 个中文 = 8 列，宽度 6 时只保留 3 个
        String t = AcodeTerminal.truncateToWidth("一二三四", 6);
        assertEquals("一二三\033[0m", t);
        assertFalse(t.contains("四"));
    }
}
