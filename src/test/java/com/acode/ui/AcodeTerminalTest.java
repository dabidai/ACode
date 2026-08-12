package com.acode.ui;

import org.jline.utils.WCWidth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void wrapKeepsEverySegmentWithinWidth() {
        String line = "Redis 是互联网后端极其重要的基础设施，简单易用、功能强大，它不仅仅是一个缓存";
        for (String seg : AcodeTerminal.wrap(line, 20)) {
            assertTrue(displayWidth(seg) <= 20);
        }
    }

    @Test
    void wrapPreservesAllContent() {
        String line = "Redis 是互联网后端极其重要的基础设施，简单易用、功能强大，它不仅仅是一个缓存";
        List<String> segs = AcodeTerminal.wrap(line, 20);
        String joined = String.join("", segs);
        assertEquals(line, joined);
        assertTrue(segs.size() > 1);
    }

    @Test
    void wrapDoesNotCutWideCharacter() {
        List<String> segs = AcodeTerminal.wrap("一二三四五六", 6);
        assertEquals("一二三", segs.get(0));
        assertEquals("四五六", segs.get(1));
    }

    @Test
    void wrapKeepsAnsiSequenceIntact() {
        List<String> segs = AcodeTerminal.wrap("\033[31m" + "很长很长很长很长" + "\033[0m", 6);
        assertTrue(segs.get(0).startsWith("\033[31m"));
        assertTrue(segs.get(segs.size() - 1).endsWith("\033[0m"));
        // 拼接还原原文（含 ANSI）
        assertEquals("\033[31m" + "很长很长很长很长" + "\033[0m", String.join("", segs));
    }

    @Test
    void wrapOfShortLineReturnsSingleSegment() {
        List<String> segs = AcodeTerminal.wrap("abc", 10);
        assertEquals(List.of("abc"), segs);
    }

    @Test
    void wrapOfEmptyLineReturnsOneEmptySegment() {
        List<String> segs = AcodeTerminal.wrap("", 10);
        assertEquals(List.of(""), segs);
    }

    @Test
    void wrapOfAsciiLongLineBreaksExactlyAtWidth() {
        List<String> segs = AcodeTerminal.wrap("abcdefghij", 4);
        assertEquals(List.of("abcd", "efgh", "ij"), segs);
    }

    @Test
    void thumbHeightScalesWithContent() {
        assertEquals(4, AcodeTerminal.thumbHeight(40, 400));
        assertEquals(20, AcodeTerminal.thumbHeight(40, 80));
        assertEquals(1, AcodeTerminal.thumbHeight(40, 100000));
    }

    @Test
    void scrollbarCellIsTrackOutsideThumb() {
        assertEquals("\033[48;5;236m \033[0m", AcodeTerminal.scrollbarCell(0, 2, 3));
        assertEquals("\033[48;5;236m \033[0m", AcodeTerminal.scrollbarCell(5, 2, 3));
    }

    @Test
    void scrollbarCellShapesCapsule() {
        // 滑块占行 [2,5)：顶行 ▄、中间 █、底行 ▀
        assertEquals("\033[38;5;242;48;5;236m▄\033[0m", AcodeTerminal.scrollbarCell(2, 2, 3));
        assertEquals("\033[38;5;242;48;5;236m█\033[0m", AcodeTerminal.scrollbarCell(3, 2, 3));
        assertEquals("\033[38;5;242;48;5;236m▀\033[0m", AcodeTerminal.scrollbarCell(4, 2, 3));
    }

    @Test
    void scrollbarCellSingleRowThumbIsFullBlock() {
        assertEquals("\033[38;5;242;48;5;236m█\033[0m", AcodeTerminal.scrollbarCell(1, 1, 1));
    }

    @Test
    void thumbTopMapsFromFraction() {
        assertEquals(0, AcodeTerminal.thumbTop(40, 400, 0));
        assertEquals(36, AcodeTerminal.thumbTop(40, 400, 360));
        assertEquals(18, AcodeTerminal.thumbTop(40, 400, 180));
    }

    @Test
    void targetScrollOffsetMapsBottomAndTop() {
        // 10 行各折 1 段
        int[] prefix = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(0, AcodeTerminal.targetScrollOffset(10, 4, prefix, 6)); // 底部
        assertEquals(6, AcodeTerminal.targetScrollOffset(10, 4, prefix, 0)); // 顶部
    }

    @Test
    void targetScrollOffsetAccountsForWrap() {
        // 行折行数 [1,2,1,1,2,1,1,1,2,1] → 前缀和 13
        int[] prefix = {0, 1, 3, 4, 5, 7, 8, 9, 10, 12, 13};
        assertEquals(0, AcodeTerminal.targetScrollOffset(10, 4, prefix, 9)); // 底部（总折行 13-4=9）
        assertEquals(6, AcodeTerminal.targetScrollOffset(10, 4, prefix, 0)); // 顶部 clamp 到 maxS
    }

    @Test
    void displayFromFollowsScrollOffset() {
        // 10 行各折 1 段：滑块应从底（6）随回看升到顶（0）
        int[] prefix = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(6, AcodeTerminal.displayFrom(10, 4, prefix, 0));
        assertEquals(3, AcodeTerminal.displayFrom(10, 4, prefix, 3));
        assertEquals(0, AcodeTerminal.displayFrom(10, 4, prefix, 6));
    }

    @Test
    void displayFromAccountsForWrap() {
        // 行折行数 [1,2,1,1,2,1,1,1,2,1] → 前缀和 13
        int[] prefix = {0, 1, 3, 4, 5, 7, 8, 9, 10, 12, 13};
        assertEquals(9, AcodeTerminal.displayFrom(10, 4, prefix, 0)); // 底部第一显示行 = 13-4
        assertEquals(0, AcodeTerminal.displayFrom(10, 4, prefix, 6)); // 顶部 clamp 到 0，不裁掉开头
    }

    @Test
    void displayFromRoundTripsWithTargetScrollOffset() {
        // 短行：正反映射应回到同一滚动位置
        int[] prefix = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (int s = 0; s <= 6; s++) {
            int from = AcodeTerminal.displayFrom(10, 4, prefix, s);
            assertEquals(s, AcodeTerminal.targetScrollOffset(10, 4, prefix, from));
        }
    }

    @Test
    void displayRowsTopAlignsWhenContentFits() {
        String[] rows = AcodeTerminal.displayRows(List.of("a", "b"), 5, 0);
        assertEquals("a", rows[0]);
        assertEquals("b", rows[1]);
        assertEquals("", rows[2]);
        assertEquals("", rows[4]);
    }

    @Test
    void displayRowsShowsTailWhenContentOverflows() {
        String[] rows = AcodeTerminal.displayRows(List.of("a", "b", "c", "d", "e"), 3, 2);
        assertArrayEquals(new String[]{"c", "d", "e"}, rows);
    }

    @Test
    void displayRowsFromTopShowsHeadNotClipped() {
        // 全局起点 0：顶部对齐，长行开头完整显示，不会因「只看尾部」被裁
        String[] rows = AcodeTerminal.displayRows(List.of("长行第一段", "长行第二段", "b", "c"), 3, 0);
        assertArrayEquals(new String[]{"长行第一段", "长行第二段", "b"}, rows);
    }

    @Test
    void targetScrollOffsetContentFitsReturnsZero() {
        assertEquals(0, AcodeTerminal.targetScrollOffset(3, 10, new int[]{0, 1, 2, 3}, 0));
    }
}
