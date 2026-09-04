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

    // ---- 新增：appendCommitted 追加式空行语义 ----

    @Test
    void appendCommittedWritesBlankLineForLoneNewline() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.appendCommitted(sw, "\n");
        assertEquals("\r\n", sw.toString());
    }

    @Test
    void appendCommittedKeepsBlankLinesBetweenLines() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.appendCommitted(sw, "a\n\nb\n");
        assertEquals("a\r\n\r\nb\r\n", sw.toString());
    }

    // ---- 新增：等待输入帧（提示符上方 mode+分隔线，下方页脚，光标上移回提示符行） ----

    @Test
    void renderWaitingFrameReservesPromptRowAndMovesCursorBackUp() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.renderWaitingFrame(sw, "MODE", "DIV", "FOOTDIV", "FOOTMODEL");
        // 模式提示 + 分隔线（提示符上方）→ 预留提示符空行 → 页脚分隔线 + 模型信息（下方）→ 光标上移 3 行
        assertEquals("MODE\r\n" + "DIV\r\n" + "\r\n" + "FOOTDIV\r\n" + "FOOTMODEL\r\n" + "\033[3A",
                sw.toString());
        assertEquals(2, renderer.linesSinceFrame(), "模式提示行到光标恒为 2 行（Shift+Tab 上移定位用）");
    }

    // ---- 新增：尾行协议（流式计时行恒为屏幕最后一行，原地刷新偏移恒为 1） ----

    @Test
    void setTailRowCreatesRowBelowCursorAndCountsOneLine() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(sw, "⏱ 0.5s");
        assertEquals("⏱ 0.5s\r\n", sw.toString(), "从无到有：直接写一行、光标下移 1");
        assertEquals(1, renderer.linesSinceFrame());
    }

    @Test
    void setTailRowRefreshesInPlaceWithConstantOffsetOne() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        StringWriter sw = new StringWriter();
        renderer.setTailRow(sw, "⏱ 1.0s");
        assertEquals("\033[1A\r\033[2K" + "⏱ 1.0s\r\n", sw.toString(),
                "已有尾行：抬起 1 行重写，净位移 0，不需要任何跨调用行数计数");
        assertEquals(1, renderer.linesSinceFrame(), "原地刷新不改变行数记账");
    }

    @Test
    void clearTailRowLeavesCursorOnTheBlankRowItVacated() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        StringWriter sw = new StringWriter();
        renderer.clearTailRow(sw);
        assertEquals("\033[1A\r\033[2K", sw.toString(), "抬起并清空，光标停在空白行、不留多余空行");
        assertEquals(0, renderer.linesSinceFrame());
    }

    @Test
    void clearTailRowWithoutTailRowWritesNothing() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        StringWriter sw = new StringWriter();
        renderer.clearTailRow(sw);
        assertEquals("", sw.toString());
        assertEquals(0, renderer.linesSinceFrame(), "无尾行时不得把记账减成负数");
    }

    @Test
    void appendCommittedLiftsTailRowAndResticksItAtBottom() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        StringWriter sw = new StringWriter();
        renderer.appendCommitted(sw, "x\ny\n");
        assertEquals("\033[1A\r\033[2K" + "x\r\n" + "y\r\n" + "⏱ 0.5s\r\n", sw.toString(),
                "尾行必须恒为最后一行已提交内容");
        assertEquals(3, renderer.linesSinceFrame(), "净位移仍是新增行数（抬起 -1、贴回 +1）");
    }

    @Test
    void appendCommittedDoesNotLiftTailRowForEmptyText() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        StringWriter sw = new StringWriter();
        renderer.appendCommitted(sw, "");
        assertEquals("", sw.toString(), "空文本是 no-op，不得为它抬起尾行（否则净位移变 -1）");
    }

    // ---- 新增：等待帧新鲜度与光标以下残留 ----

    @Test
    void atWaitingFrameIsTrueOnlyForUntouchedWaitingFrame() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        StringWriter sw = new StringWriter();
        assertTrue(!renderer.atWaitingFrame(), "尚未渲染等待帧时不算新鲜帧");
        renderer.renderWaitingFrame(sw, "MODE", "DIV", "FOOTDIV", "FOOTMODEL");
        assertTrue(renderer.atWaitingFrame(), "刚渲染完的等待帧是 Shift+Tab 原地重写的唯一合法前提");
        renderer.appendCommitted(sw, "输出");
        assertTrue(!renderer.atWaitingFrame(), "任何追加都把模式提示行推远，原地重写会改坏屏幕");
    }

    @Test
    void clearBelowCursorErasesFooterResidueAndResetsState() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        StringWriter sw = new StringWriter();
        renderer.redraw(sw, List.of("a", "b"));
        renderer.renderWaitingFrame(sw, "MODE", "DIV", "FOOTDIV", "FOOTMODEL");
        sw.getBuffer().setLength(0);
        renderer.clearBelowCursor(sw);
        assertEquals("\033[J", sw.toString(), "擦掉提示符下方仍在屏上的页脚两行，且不移动光标");
        assertEquals(0, renderer.rowsWritten());
        assertTrue(!renderer.atWaitingFrame(), "帧状态作废，Shift+Tab 转而攒延迟消息");
    }

    @Test
    void clearBelowCursorDropsTailRow() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        renderer.clearBelowCursor(new StringWriter());
        StringWriter sw = new StringWriter();
        renderer.setTailRow(sw, "⏱ 1.0s");
        assertEquals("⏱ 1.0s\r\n", sw.toString(), "尾行已随擦除作废，新尾行不应再抬起");
    }

    @Test
    void clearScreenDropsTailRow() {
        LiveRegionRenderer renderer = new LiveRegionRenderer(20, 10);
        renderer.setTailRow(new StringWriter(), "⏱ 0.5s");
        StringWriter sw = new StringWriter();
        renderer.clearScreen(sw);
        renderer.setTailRow(sw, "⏱ 1.0s");
        assertEquals("\033[2J\033[H" + "⏱ 1.0s\r\n", sw.toString(), "整屏清空后尾行不再存在于屏上");
    }
}
