package com.acode.ui;

import org.jline.utils.WCWidth;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRegionRendererTest {

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

    // ---- wrap 迁移用例（原 AcodeTerminalTest） ----

    @Test
    void wrapKeepsEverySegmentWithinWidth() {
        String line = "Redis 是互联网后端极其重要的基础设施，简单易用、功能强大，它不仅仅是一个缓存";
        for (String seg : LiveRegionRenderer.wrap(line, 20)) {
            assertTrue(displayWidth(seg) <= 20);
        }
    }

    @Test
    void wrapPreservesAllContent() {
        String line = "Redis 是互联网后端极其重要的基础设施，简单易用、功能强大，它不仅仅是一个缓存";
        List<String> segs = LiveRegionRenderer.wrap(line, 20);
        String joined = String.join("", segs);
        assertEquals(line, joined);
        assertTrue(segs.size() > 1);
    }

    @Test
    void wrapDoesNotCutWideCharacter() {
        List<String> segs = LiveRegionRenderer.wrap("一二三四五六", 6);
        assertEquals("一二三", segs.get(0));
        assertEquals("四五六", segs.get(1));
    }

    @Test
    void wrapKeepsAnsiSequenceIntact() {
        List<String> segs = LiveRegionRenderer.wrap("\033[31m" + "很长很长很长很长" + "\033[0m", 6);
        assertTrue(segs.get(0).startsWith("\033[31m"));
        assertTrue(segs.get(segs.size() - 1).endsWith("\033[0m"));
        // 拼接还原原文（含 ANSI）
        assertEquals("\033[31m" + "很长很长很长很长" + "\033[0m", String.join("", segs));
    }

    @Test
    void wrapOfShortLineReturnsSingleSegment() {
        List<String> segs = LiveRegionRenderer.wrap("abc", 10);
        assertEquals(List.of("abc"), segs);
    }

    @Test
    void wrapOfEmptyLineReturnsOneEmptySegment() {
        List<String> segs = LiveRegionRenderer.wrap("", 10);
        assertEquals(List.of(""), segs);
    }

    @Test
    void wrapOfAsciiLongLineBreaksExactlyAtWidth() {
        List<String> segs = LiveRegionRenderer.wrap("abcdefghij", 4);
        assertEquals(List.of("abcd", "efgh", "ij"), segs);
    }

    // ---- 新增：纯函数 ----

    @Test
    void upRowsClampsToScreenHeightMinusOne() {
        assertEquals(4, LiveRegionRenderer.upRows(10, 5));
        assertEquals(4, LiveRegionRenderer.upRows(4, 5));
        assertEquals(0, LiveRegionRenderer.upRows(0, 5));
    }

    @Test
    void upRowsReturnsZeroWhenHeightIsOne() {
        assertEquals(0, LiveRegionRenderer.upRows(10, 1));
        assertEquals(0, LiveRegionRenderer.upRows(10, 0));
    }

    @Test
    void visibleSegsKeepsOnlyTailSegments() {
        List<String> segs = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("c", "d", "e"), LiveRegionRenderer.visibleSegs(segs, 4));
        assertEquals(List.of("e"), LiveRegionRenderer.visibleSegs(segs, 2));
        assertEquals(segs, LiveRegionRenderer.visibleSegs(segs, 10));
        assertEquals(List.of(), LiveRegionRenderer.visibleSegs(segs, 1));
    }

    // ---- 新增：写序列 ----

    @Test
    void redrawWritesClearThenSegmentsOnFirstFrame() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(sw, List.of("hello", "world"));
        // 已写行数 0：无上移序列
        assertEquals("\033[J" + "hello\r\n" + "world\r\n", sw.toString());
        assertEquals(2, renderer.rowsWritten());
    }

    @Test
    void redrawWritesUpMoveClearAndSegmentsOnSubsequentFrame() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(new StringWriter(), List.of("hello", "world"));
        StringWriter sw = new StringWriter();
        renderer.redraw(sw, List.of("a"));
        assertEquals("\033[2A" + "\033[J" + "a\r\n", sw.toString());
        assertEquals(1, renderer.rowsWritten());
    }

    @Test
    void redrawFullWidthLineEndsWithCarriageReturn() {
        // 内容宽度恰等于终端宽度：行尾 \r 化解 pending-wrap 幻影空行（R4）
        String line = "a".repeat(20);
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(sw, List.of(line));
        assertEquals("\033[J" + line + "\r\n", sw.toString());
    }

    @Test
    void redrawRendersOnlyVisibleSuffixWhenOverflowingScreen() {
        // 高度 3 → 可见段上限 2；超屏时只重绘末尾可见后缀（R3）
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 3);
        StringWriter sw = new StringWriter();
        renderer.redraw(sw, List.of("line1", "line2", "line3"));
        assertEquals("\033[J" + "line2\r\n" + "line3\r\n", sw.toString());
        assertEquals(2, renderer.rowsWritten());
    }

    @Test
    void redrawAfterSizeChangeResetsRowsWritten() {
        // 终端宽高变化后旧已写行数失效：先归零重锚定，不再上移旧行数（R3）
        int[] w = {20};
        int[] h = {10};
        LiveRegionRenderer renderer = new LiveRegionRenderer(() -> w[0], () -> h[0]);
        renderer.redraw(new StringWriter(), List.of("a", "b"));
        assertEquals(2, renderer.rowsWritten());
        w[0] = 30;
        h[0] = 12;
        StringWriter sw = new StringWriter();
        renderer.redraw(sw, List.of("c"));
        assertEquals("\033[J" + "c\r\n", sw.toString());
        assertEquals(1, renderer.rowsWritten());
    }

    @Test
    void clearWritesClearSequenceAndResetsRowsWritten() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(new StringWriter(), List.of("a", "b"));
        assertEquals(2, renderer.rowsWritten());
        StringWriter sw = new StringWriter();
        renderer.clear(sw);
        assertEquals("\033[2A" + "\033[J", sw.toString());
        assertEquals(0, renderer.rowsWritten());
    }

    @Test
    void appendCommittedWritesLineAndDoesNotChangeRowsWritten() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(new StringWriter(), List.of("a"));
        assertEquals(1, renderer.rowsWritten());
        StringWriter sw = new StringWriter();
        renderer.appendCommitted(sw, "hello");
        assertEquals("hello\r\n", sw.toString());
        assertEquals(1, renderer.rowsWritten(), "appendCommitted 不计已写行数");
    }

    @Test
    void appendCommittedSplitsMultiLineAndStripsCarriageReturn() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.appendCommitted(sw, "a\r\nb\n");
        assertEquals("a\r\nb\r\n", sw.toString());
    }

    @Test
    void commitRegionResetsRowsWrittenWithoutWriting() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redraw(new StringWriter(), List.of("a", "b", "c"));
        assertEquals(3, renderer.rowsWritten());
        renderer.commitRegion();
        assertEquals(0, renderer.rowsWritten());
    }

    // ---- 新增：footer 追加式路径 ----

    @Test
    void truncateToWidthLeavesShortLineUnchanged() {
        assertEquals("abc", LiveRegionRenderer.truncateToWidth("abc", 10));
        assertEquals("", LiveRegionRenderer.truncateToWidth("", 10));
    }

    @Test
    void truncateToWidthCutsLongLineToWithinWidth() {
        String cut = LiveRegionRenderer.truncateToWidth("abcdefghij", 4);
        assertEquals(4, displayWidth(cut));
        assertTrue(cut.startsWith("abcd"), "截断保留最长前缀");
        assertTrue(cut.endsWith("\033[0m"), "截断处补 RESET 防颜色泄漏");
    }

    @Test
    void truncateToWidthHandlesCjkWidth() {
        // CJK 每字 2 列：宽度 6 恰好放下「一二三」，下一个字放不下则截断
        assertEquals("一二三", LiveRegionRenderer.truncateToWidth("一二三四五六", 6).replace("\033[0m", ""));
    }

    @Test
    void truncateToWidthKeepsAnsiSequenceIntact() {
        String cut = LiveRegionRenderer.truncateToWidth("\033[31m" + "一二三四五" + "\033[0m", 6);
        assertTrue(cut.startsWith("\033[31m"), "保留前导颜色");
        assertEquals(6, displayWidth(cut), "截断后显示宽度 ≤ 终端宽度");
        assertTrue(cut.endsWith("\033[0m"));
    }

    @Test
    void truncateToWidthOfWidthZeroReturnsEmpty() {
        assertEquals("", LiveRegionRenderer.truncateToWidth("abc", 0));
        assertEquals("", LiveRegionRenderer.truncateToWidth("abc", -1));
    }

    @Test
    void redrawFooterWritesCommittedThenTruncatedFooterOnFirstFrame() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redrawFooter(sw, List.of("line1"), List.of("abcdefghij"));
        // 首帧无上移：清到屏尾 → 已提交行 → footer 截断到 20 内
        assertEquals("\033[J" + "line1\r\n" + "abcdefghij\r\n", sw.toString());
        assertEquals(1, renderer.footerRows());
    }

    @Test
    void redrawFooterWritesUpMoveOnSubsequentFrame() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redrawFooter(new StringWriter(), List.of(), List.of("a"));
        assertEquals(1, renderer.footerRows());
        StringWriter sw = new StringWriter();
        renderer.redrawFooter(sw, List.of("b"), List.of("c", "d"));
        assertEquals("\033[1A" + "\033[J" + "b\r\n" + "c\r\n" + "d\r\n", sw.toString());
        assertEquals(2, renderer.footerRows());
    }

    @Test
    void redrawFooterTruncatesFooterRowsToWidth() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(4, 10);
        renderer.redrawFooter(sw, List.of(), List.of("abcdefghij"));
        assertEquals("\033[J" + "abcd\033[0m\r\n", sw.toString(), "footer 行截断到终端宽度");
    }

    @Test
    void commitFooterClearsFooterAndWritesCommitted() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redrawFooter(new StringWriter(), List.of(), List.of("a", "b"));
        assertEquals(2, renderer.footerRows());
        StringWriter sw = new StringWriter();
        renderer.commitFooter(sw, List.of("done"));
        assertEquals("\033[2A" + "\033[J" + "done\r\n", sw.toString());
        assertEquals(0, renderer.footerRows());
    }

    @Test
    void redrawFooterAfterSizeChangeResetsFooterRows() {
        int[] w = {20};
        int[] h = {10};
        LiveRegionRenderer renderer = new LiveRegionRenderer(() -> w[0], () -> h[0]);
        renderer.redrawFooter(new StringWriter(), List.of(), List.of("a"));
        assertEquals(1, renderer.footerRows());
        w[0] = 30;
        StringWriter sw = new StringWriter();
        renderer.redrawFooter(sw, List.of(), List.of("b"));
        assertEquals("\033[J" + "b\r\n", sw.toString(), "尺寸变化后不再上移旧 footer 行数");
        assertEquals(1, renderer.footerRows());
    }

    @Test
    void commitRegionResetsFooterRows() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.redrawFooter(new StringWriter(), List.of(), List.of("a", "b", "c"));
        assertEquals(3, renderer.footerRows());
        renderer.commitRegion();
        assertEquals(0, renderer.footerRows());
        assertEquals(0, renderer.rowsWritten());
    }
}
