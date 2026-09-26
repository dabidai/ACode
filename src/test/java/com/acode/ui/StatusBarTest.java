package com.acode.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatusBar} 与 {@link AnsiPalette} 的契约测试。
 * 断言依据的是对外契约（色板字面量、宽度不变量、进度条格数、截断方向），不是实现里怎么算的。
 * 宽度一律用 {@link StatusBar#displayWidth(String)}（CJK 按 2 列）度量——框线折行即帧错位。
 */
class StatusBarTest {

    /** 触发路径左截断用的长路径：盘符 + 中文目录名 + 很深的子目录。 */
    private static final String LONG_PATH = "D:\\Code\\claude\\ACode\\文档目录\\很深的子目录\\project";
    private static final String DEEPEST_DIR = "很深的子目录";
    private static final String POLICY_HINT = "/permission";

    /** 去掉 SGR 序列（\033[0m、\033[36m、\033[1;33m 等），拿回纯文本再量宽度。 */
    private static String stripAnsi(String s) {
        return PATTERN.matcher(s).replaceAll("");
    }

    private static final Pattern PATTERN = Pattern.compile("\033\\[[0-9;]*m");

    @Test
    void reservedInputRowsLeaveRoomForTheStartupBanner() {
        assertEquals(8, StatusBar.frameReservedRows(24));
        assertEquals(6, StatusBar.frameReservedRows(20));
        assertEquals(0, StatusBar.frameReservedRows(5));
    }

    private static int shownWidth(String s) {
        return StatusBar.displayWidth(stripAnsi(s));
    }

    /** 切出信息行里进度条那一段（"ctx " 与百分比之间的块元素），路径不含块字符所以不会误切。 */
    private static String barSection(String line) {
        String plain = stripAnsi(line);
        int ctx = plain.indexOf("ctx ");
        assertTrue(ctx >= 0, "信息行应含 'ctx ' 前缀，实际：" + plain);
        int start = ctx + "ctx ".length();
        int end = plain.indexOf(' ', start);
        assertTrue(end > start, "信息行应含 'ctx <进度条> <百分比>' 结构，实际：" + plain);
        return plain.substring(start, end);
    }

    // ---------- AnsiPalette：与 PR #2 色板对齐的验收依据 ----------

    @Test
    void paletteLiteralValuesMatchPr2Palette() {
        assertEquals("\033[90m", AnsiPalette.DIM, "DIM 色值被改动（PR #2 色板对齐验收依据）");
        assertEquals("\033[1;33m", AnsiPalette.MODE, "MODE 色值被改动（PR #2 色板对齐验收依据）");
        assertEquals("\033[1;36m", AnsiPalette.MODEL, "MODEL 色值被改动（PR #2 色板对齐验收依据）");
        assertEquals("\033[36m", AnsiPalette.BAR, "BAR 色值被改动（PR #2 色板对齐验收依据）");
        assertEquals("\033[0m", AnsiPalette.RESET, "RESET 色值被改动（PR #2 色板对齐验收依据）");
    }

    // ---------- ctxBarFilled ----------

    @Test
    void ctxBarFilledAtBoundaries() {
        assertEquals(0, StatusBar.ctxBarFilled(0.0), "0 占用应 0 格，否则看着像有占用");
        assertEquals(5, StatusBar.ctxBarFilled(0.5), "0.5 应亮 5 格");
        assertEquals(10, StatusBar.ctxBarFilled(1.0), "满占用应 10 格");
    }

    @Test
    void ctxBarFilledTinyNonZeroKeepsOneCell() {
        assertEquals(1, StatusBar.ctxBarFilled(0.01), "非零占用至少亮 1 格，否则永远看着像空的");
        assertEquals(1, StatusBar.ctxBarFilled(0.04), "非零占用至少亮 1 格，否则永远看着像空的");
        assertEquals(1, StatusBar.ctxBarFilled(0.05), "0.05 四舍五入得 1 格");
    }

    @Test
    void ctxBarFilledClampsOutOfRange() {
        assertEquals(0, StatusBar.ctxBarFilled(-0.5), "负值夹到 0 格");
        assertEquals(10, StatusBar.ctxBarFilled(1.5), ">1 夹到满格");
        assertEquals(10, StatusBar.ctxBarFilled(42.0), "远大于 1 也夹到满格");
    }

    // ---------- ctxPercentText ----------

    @Test
    void ctxPercentTextAtOrAboveTenPercentUsesInteger() {
        assertEquals("12%", StatusBar.ctxPercentText(0.12), "≥10% 取整");
        assertEquals("100%", StatusBar.ctxPercentText(1.0), "1.0 应是 100%");
    }

    @Test
    void ctxPercentTextBelowTenPercentKeepsOneDecimal() {
        assertEquals("9.9%", StatusBar.ctxPercentText(0.099), "(0,10%) 保留一位小数，大窗口下才看得出变化");
        assertEquals("0.0%", StatusBar.ctxPercentText(0.0), "0 占用显示 0.0%，且小数点是 '.'");
    }

    @Test
    void ctxPercentTextClampsOutOfRange() {
        assertEquals("0.0%", StatusBar.ctxPercentText(-0.5), "负值夹到 0.0%");
        assertEquals("100%", StatusBar.ctxPercentText(1.5), ">1 夹到 100%");
    }

    // ---------- divider ----------

    @Test
    void dividerCountsExactlyWidthBars() {
        assertEquals(40, countOf(StatusBar.divider(40), '─'), "divider 应有 width 个 ─");
        assertEquals(3, countOf(StatusBar.divider(3), '─'), "divider 应有 width 个 ─");
        assertEquals(1, countOf(StatusBar.divider(1), '─'), "divider 应有 width 个 ─");
    }

    @Test
    void dividerNonPositiveWidthStillDrawsOneBar() {
        assertEquals(1, countOf(StatusBar.divider(0), '─'), "width=0 时至少 1 个 ─");
        assertEquals(1, countOf(StatusBar.divider(-7), '─'), "width<0 时至少 1 个 ─");
    }

    @Test
    void dividerWrappedInDimAndReset() {
        String line = StatusBar.divider(5);
        assertTrue(line.startsWith(AnsiPalette.DIM), "divider 应以 DIM 色开头，实际：" + line);
        assertTrue(line.endsWith(AnsiPalette.RESET), "divider 应以 RESET 收尾，实际：" + line);
    }

    // ---------- 宽度不变量（重点） ----------

    @Test
    void displayWidthCountsCjkAsTwoColumns() {
        assertEquals(3, StatusBar.displayWidth("abc"), "ASCII 每字符 1 列");
        assertEquals(4, StatusBar.displayWidth("中文"), "CJK 等宽字符按 2 列计，否则宽度不变量会算少而真终端折行");
        assertEquals(4, StatusBar.displayWidth("a中b"), "ASCII 与 CJK 混排：1+2+1");
        assertEquals(0, StatusBar.displayWidth(""), "空串 0 列");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5, 10, 20, 40, 80, 120})
    void modeLineNeverExceedsWidth(int width) {
        String line = StatusBar.modeLine("default", width);
        assertTrue(shownWidth(line) <= width,
                "modeLine 显示宽度 " + shownWidth(line) + " 超过 width=" + width + "，框线会折行：" + line);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 10, 20, 40, 80, 120})
    void framedInputPromptHasThreeRowsWithoutRightEdgeWrap(int width) {
        String[] rows = stripAnsi(StatusBar.framedInputPrompt("default", width)).split("\n", -1);
        assertEquals(3, rows.length);
        assertTrue(rows[0].startsWith("["));
        assertTrue(StatusBar.displayWidth(rows[0]) < width);
        assertEquals(width - 1, StatusBar.displayWidth(rows[1]));
        assertEquals("> ", rows[2]);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5, 10, 20, 40, 80, 120})
    void infoLineNeverExceedsWidth(int width) {
        String line = StatusBar.infoLine("claude-sonnet-4-5-20250929", 0.42, LONG_PATH, width);
        assertTrue(shownWidth(line) <= width,
                "infoLine 显示宽度 " + shownWidth(line) + " 超过 width=" + width + "，框线会折行：" + line);
    }

    // ---------- 进度条恰好 10 列 ----------

    @Test
    void infoLineBarIsExactlyTenDisplayColumns() {
        String line = StatusBar.infoLine("m", 0.4, "p", 200);
        assertTrue(stripAnsi(line).contains("░") && stripAnsi(line).contains("▓"),
                "ctx 非满非零时进度条应同时含亮格与暗格，实际：" + stripAnsi(line));
        assertEquals(10, StatusBar.displayWidth(barSection(line)),
                "进度条必须正好占 10 列（▓/░ 若被 wcwidth 判成 2 列，整帧宽度数学就错了），实际：" + barSection(line));
    }

    @Test
    void infoLineBarCellCountFollowsFraction() {
        String plain = stripAnsi(StatusBar.infoLine("m", 0.4, "p", 200));
        assertTrue(plain.contains("▓▓▓▓░░░░░░ 40%"),
                "0.4 应亮 4 格并显示 40%，实际：" + plain);
    }

    @Test
    void infoLineStylesModelBrightCellsAndPath() {
        String line = StatusBar.infoLine("m", 0.4, "p", 200);
        assertTrue(line.contains(AnsiPalette.MODEL + "m" + AnsiPalette.RESET),
                "模型名应用 MODEL 色，实际：" + line);
        assertTrue(line.contains(AnsiPalette.BAR + "▓▓▓▓"),
                "亮格应用 BAR 色，实际：" + line);
        assertTrue(line.contains(AnsiPalette.DIM + "░░░░░░"),
                "暗格应用 DIM 色，实际：" + line);
        assertTrue(stripAnsi(line).endsWith(" · p"), "路径放得下时应原样显示，实际：" + stripAnsi(line));
    }

    // ---------- modeLine 内容 ----------

    @Test
    void modeLineCarriesModeNameAndPolicyHint() {
        String line = StatusBar.modeLine("default", 120);
        String plain = stripAnsi(line);
        assertTrue(plain.contains("[default]"), "充足宽度下应含 [default]，实际：" + plain);
        assertTrue(plain.contains(POLICY_HINT), "提示文案应含 " + POLICY_HINT + "，实际：" + plain);
        assertTrue(line.contains(AnsiPalette.MODE + "[default]" + AnsiPalette.RESET),
                "模式名应用 MODE 色，实际：" + line);
        assertTrue(line.contains(AnsiPalette.DIM + " · " + POLICY_HINT),
                "提示段应用 DIM 色，实际：" + line);
    }

    @Test
    void modeLineTooNarrowFallsBackToPlainTextWithinWidth() {
        String line = StatusBar.modeLine("default", 5);
        assertEquals("[def…", line, "放不下时应丢色截断并补省略号 …：4 列前缀 + 1 列省略号");
        assertTrue(line.indexOf('\033') < 0, "降级输出不得残留 ANSI 转义，实际：" + line);
    }

    @Test
    void modeLineTruncationBoundaryAtExactFitWidth() {
        // 「[default] · /permission <模式>」的明文总列数即截断分界：差 1 列截断补 …，恰放得下则原样上色
        String plain = "[default] · /permission <模式>";
        int fitWidth = StatusBar.displayWidth(plain);

        String narrow = StatusBar.modeLine("default", fitWidth - 1);
        assertTrue(narrow.endsWith("…"), "差 1 列放不下时应截断并补省略号，实际：" + narrow);
        int narrowWidth = shownWidth(narrow);
        assertTrue(narrowWidth <= fitWidth - 1 && narrowWidth >= fitWidth - 2,
                "截断宽度应落在 width-1 或 width-2（顶在宽字符上宁可少占 1 列也不切断），实际：" + narrow);

        String exact = StatusBar.modeLine("default", fitWidth);
        assertEquals(plain, stripAnsi(exact), "恰放得下时应完整渲染原文，不截断");
        assertFalse(stripAnsi(exact).contains("…"), "恰放得下时不应出现省略号");
        assertTrue(exact.contains(AnsiPalette.MODE + "[default]"), "恰放得下时应保留 MODE 上色");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 9, 14, 20, 24, 26, 28})
    void modeLineTruncatedFillsWidthExactlyWithEllipsis(int width) {
        // 截断点落在 ASCII 前缀内时：(width-1) 列前缀 + 1 列省略号，宽度恰为 width
        String line = StatusBar.modeLine("default", width);

        assertEquals(width, shownWidth(line), "截断后总显示宽度应恰为 width，实际：" + line);
        assertTrue(line.endsWith("…"), "截断输出应以省略号收尾，实际：" + line);
        assertTrue(line.indexOf('\033') < 0, "截断降级不得残留 ANSI 转义，实际：" + line);
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 27, 29})
    void modeLineTruncationAtWideCharLosesAtMostOneColumn(int width) {
        // 截断点恰落在 2 列宽字符（<模式>）上：宁可少占 1 列也不切断，宽度落在 [width-1, width]
        String line = StatusBar.modeLine("default", width);

        int w = shownWidth(line);
        assertTrue(w <= width && w >= width - 1,
                "顶在宽字符上截断时应少占至多 1 列，实际宽度 " + w + "，width=" + width + "：" + line);
        assertTrue(line.endsWith("…"), "截断输出应以省略号收尾，实际：" + line);
    }

    @Test
    void modeLineTinyWidthsReturnExactContentWithoutException() {
        assertEquals("…", StatusBar.modeLine("default", 1), "宽度 1：只有省略号本身");
        assertEquals("[…", StatusBar.modeLine("default", 2), "宽度 2：1 列前缀 + 省略号");
        assertEquals("", StatusBar.modeLine("default", 0), "宽度 0 返回空串，不抛异常");
        assertEquals("", StatusBar.modeLine("default", -3), "宽度为负同样返回空串");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25})
    void modeLineWithCjkModeNeverSplitsWideCharNorExceedsOddWidth(int width) {
        // CJK 模式名：截断点落在 2 列宽字符上时宁可少占 1 列也不切断，奇数宽度最容易踩中
        String plain = "[默认] · /permission <模式>";
        String line = StatusBar.modeLine("默认", width);

        assertTrue(shownWidth(line) <= width,
                "奇数宽度下显示宽度仍不得超宽，实际 " + shownWidth(line) + " > " + width + "：" + line);
        if (line.endsWith("…")) {
            String prefix = line.substring(0, line.length() - 1);
            assertTrue(plain.startsWith(prefix),
                    "截断必须停在码点边界（不得切进宽字符中间），前缀应为原文前缀：" + prefix);
        }
    }

    // ---------- 路径左截断 ----------

    @Test
    void infoLineTruncatesPathFromLeftKeepingDeepestDir() {
        // 头部（model · ctx <10 格> 42% · ）约 29 列、路径约 50 列：60 列必定触发截断，
        // 且尾部容得下「很深的子目录\project」，足以区分左截断（保留尾部）与右截断（保留盘符）。
        int width = 60;
        String rendered = StatusBar.infoLine("model", 0.42, LONG_PATH, width);
        String plain = stripAnsi(rendered);
        assertTrue(plain.contains("…"), "路径放不下时应从左侧截断并加 …，实际：" + plain);
        assertTrue(plain.contains(DEEPEST_DIR), "应保留最深的目录名 " + DEEPEST_DIR + "，实际：" + plain);
        assertTrue(plain.endsWith("project"), "应保留最深一层目录名，实际：" + plain);
        assertFalse(plain.contains("D:\\"), "盘符属于可牺牲部分，不应保留，实际：" + plain);
        assertTrue(shownWidth(rendered) <= width, "截断后仍须满足宽度不变量，实际宽度 " + shownWidth(rendered));
    }

    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
