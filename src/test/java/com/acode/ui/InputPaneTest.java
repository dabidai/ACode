package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shift+Tab 原地重写模式提示行的转义序列（纯函数）。这段序列交给 JLine 的 printAbove 打印，
 * 因此必须净行位移为 0、且以 \n 结尾——否则 printAbove 会走 println 多补一行，随后的重绘就错位。
 */
class InputPaneTest {

    @Test
    void sequenceMovesUpToTargetRowClearsItAndRewrites() {
        String seq = InputPane.rowRewriteSequence(LiveRegionRenderer.WAITING_FRAME_ROWS, "[acceptEdits]");
        assertTrue(seq.startsWith("\033[2A"), "应上移到输入区顶行上方 2 行的模式提示行，实际：" + show(seq));
        assertTrue(seq.contains("\r\033[2K"), "应回列 0 并清行，避免旧文本残留尾巴");
        assertTrue(seq.contains("[acceptEdits]"), "应写入新的模式提示文本");
    }

    @Test
    void sequenceEndsWithNewlineSoPrintAboveTakesPrintBranch() {
        for (int rowsAbove = 1; rowsAbove <= 4; rowsAbove++) {
            String seq = InputPane.rowRewriteSequence(rowsAbove, "MODE");
            assertTrue(seq.endsWith("\r\n"),
                    "必须以 \\n 结尾：printAbove 只有对 \\n 结尾的串才走 print 而非 println，实际：" + show(seq));
        }
    }

    @Test
    void sequenceHasZeroNetRowDisplacement() {
        for (int rowsAbove = 1; rowsAbove <= 4; rowsAbove++) {
            String seq = InputPane.rowRewriteSequence(rowsAbove, "MODE");
            int up = cursorRows(seq, 'A');
            int down = cursorRows(seq, 'B') + countChar(seq, '\n');
            assertEquals(rowsAbove, up, "上移行数应等于 rowsAbove");
            assertEquals(up, down,
                    "净行位移必须为 0，光标要回到输入区顶行第 0 列让 JLine 重绘提示符，实际：" + show(seq));
        }
    }

    @Test
    void singleRowRewriteEmitsNoDownMoveSequence() {
        String seq = InputPane.rowRewriteSequence(1, "MODE");
        assertEquals(-1, seq.indexOf('B'), "rowsAbove=1 时无需下移序列，只靠结尾换行回落，实际：" + show(seq));
    }

    @Test
    void textIsFollowedByCarriageReturnToCancelPendingWrap() {
        // 模式提示行的显示宽度可能恰等于终端宽度：此时终端处于「待换行」状态，随后的 \n 会多走一行。
        // 紧跟文本的 \r 把光标拉回列 0，化解这个幻影换行。
        String text = "x".repeat(80);
        String seq = InputPane.rowRewriteSequence(2, text);
        int end = seq.indexOf(text) + text.length();
        assertEquals('\r', seq.charAt(end), "文本后必须紧跟 \\r，实际：" + show(seq.substring(end)));
    }

    /** 统计 \033[N<finalByte> 序列的总行数（缺省参数按 1 计）。 */
    private static int cursorRows(String s, char finalByte) {
        int total = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '\033' || s.charAt(i + 1) != '[') {
                continue;
            }
            int j = i + 2;
            StringBuilder params = new StringBuilder();
            while (j < s.length() && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                params.append(s.charAt(j));
                j++;
            }
            if (j < s.length() && s.charAt(j) == finalByte) {
                total += params.length() == 0 ? 1 : Integer.parseInt(params.toString());
            }
        }
        return total;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 断言失败时把 ESC 显示成可读形式。 */
    private static String show(String s) {
        return s.replace("\033", "<ESC>").replace("\r", "<CR>").replace("\n", "<LF>\n");
    }
}
