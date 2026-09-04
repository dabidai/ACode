package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页脚信息行格式与 ctx 换算：断言去掉 ANSI 后的纯文本内容。
 */
class StatusBarTest {

    private static final String ANSI = "\033\\[[0-9;]*m";

    private static String plain(String styled) {
        return styled.replaceAll(ANSI, "");
    }

    @Test
    void infoLineShowsModelBarPercentAndFullPath() {
        String line = plain(StatusBar.infoLine(
                "agnes-2.0-flash", 0.7, "D:\\GitHub-Code\\ACode\\ACode", 200));
        assertEquals("agnes-2.0-flash · ctx ▓▓▓▓▓▓▓░░░ 70% · D:\\GitHub-Code\\ACode\\ACode", line);
    }

    @Test
    void infoLineBarAlwaysHasTenCells() {
        for (double fraction : new double[]{0, 0.03, 0.25, 0.5, 0.9, 1.0}) {
            String line = plain(StatusBar.infoLine("m", fraction, "/tmp/x", 200));
            int cells = 0;
            for (char c : line.toCharArray()) {
                if (c == '▓' || c == '░') {
                    cells++;
                }
            }
            assertEquals(10, cells, "fraction=" + fraction + " 的进度条必须恒为 10 格：" + line);
        }
    }

    @Test
    void infoLineUsesDotSeparatorNotPipe() {
        String line = plain(StatusBar.infoLine("m", 0, "/tmp/x", 200));
        assertFalse(line.contains("|"), "页脚不应再出现竖线分隔：" + line);
        assertEquals(2, line.split(" · ", -1).length - 1, "model / ctx / 路径 三段共两个分隔符：" + line);
    }

    @Test
    void infoLineTruncatesOverlongPathFromTheLeft() {
        String path = "D:\\GitHub-Code\\ACode\\ACode";
        String line = plain(StatusBar.infoLine("agnes-2.0-flash", 0, path, 55));
        assertEquals(55, line.length(), "超宽时必须截到刚好 width 列：" + line);
        assertTrue(line.contains("…"), "被截断的路径应以省略号开头：" + line);
        assertTrue(line.endsWith("ACode"), "应保留最深的目录名：" + line);
        assertFalse(line.contains("D:"), "盘符一侧应被截掉：" + line);
    }

    @Test
    void infoLineNeverExceedsWidthEvenWhenHeadAloneDoesNotFit() {
        String line = plain(StatusBar.infoLine("agnes-2.0-flash", 0.5, "D:\\GitHub-Code", 20));
        assertEquals(20, line.length(), "极窄终端也不许折行（页脚多一行会破坏等待帧行数）：" + line);
    }

    @Test
    void ctxBarFilledRoundsToTenCells() {
        assertEquals(0, StatusBar.ctxBarFilled(0));
        assertEquals(1, StatusBar.ctxBarFilled(0.001), "非零占用至少亮 1 格，否则看着永远空");
        assertEquals(3, StatusBar.ctxBarFilled(0.25));
        assertEquals(7, StatusBar.ctxBarFilled(0.7));
        assertEquals(8, StatusBar.ctxBarFilled(0.75));
        assertEquals(10, StatusBar.ctxBarFilled(1.0));
    }

    @Test
    void ctxBarFilledClampsOutOfRange() {
        assertEquals(0, StatusBar.ctxBarFilled(-0.5));
        assertEquals(10, StatusBar.ctxBarFilled(3.0));
    }

    @Test
    void ctxPercentTextKeepsOneDecimalBelowTenPercent() {
        assertEquals("0.0%", StatusBar.ctxPercentText(0));
        assertEquals("0.1%", StatusBar.ctxPercentText(0.0012), "百万级窗口下取整会长期显示 0%");
        assertEquals("5.0%", StatusBar.ctxPercentText(0.05));
        assertEquals("9.0%", StatusBar.ctxPercentText(0.09));
    }

    @Test
    void ctxPercentTextRoundsToIntegerFromTenPercentUp() {
        assertEquals("10%", StatusBar.ctxPercentText(0.1));
        assertEquals("70%", StatusBar.ctxPercentText(0.7));
        assertEquals("100%", StatusBar.ctxPercentText(1.0));
        assertEquals("100%", StatusBar.ctxPercentText(2.0), "超出窗口也应钳到 100%");
        assertEquals("0.0%", StatusBar.ctxPercentText(-1), "负值钳到 0");
    }
}
