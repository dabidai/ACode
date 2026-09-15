package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「输入框钉底」机制的纯计算部分：行号换算、位移钳位、CPR 响应解析。
 *
 * <p>只测包可见静态方法——{@code pin} / {@code unpin} 需要真实 JLine Terminal（要能应答
 * CPR 查询、要能读写 reader），测试路径不构造本类实例；它们的对外行为（钉底与否、
 * 挪回原处）留给真机手测。
 */
class BottomAnchorTest {

    // ---- promptFirstRow：提示符首行落在第几行（1 基） ----

    @Test
    void promptFirstRowPinsRealTerminalGeometry() {
        assertEquals(43, BottomAnchor.promptFirstRow(47, 2, 3),
                "真机实测：47 行终端 · 2 行页脚 · 3 行提示符 → 提示符首行应在第 43 行");
    }

    @Test
    void promptFirstRowShrinksWithTerminalHeight() {
        assertEquals(26, BottomAnchor.promptFirstRow(30, 2, 3),
                "同样几何下终端矮 17 行，提示符首行应同步上移 17 行");
    }

    @Test
    void promptFirstRowWithoutDecorationSitsRightAboveFooter() {
        assertEquals(28, BottomAnchor.promptFirstRow(30, 2, 1),
                "无装饰的裸提示符占 1 行时应紧贴页脚上沿（height - footerRows）");
    }

    // ---- rowsToMove：需要下移多少行 ----

    @Test
    void rowsToMoveReturnsDistanceWhenCursorIsAboveTarget() {
        assertEquals(42, BottomAnchor.rowsToMove(1, 43),
                "光标在第 1 行、目标第 43 行 → 下移 42 行");
    }

    @Test
    void rowsToMoveReturnsZeroWhenCursorIsAtTarget() {
        assertEquals(0, BottomAnchor.rowsToMove(43, 43),
                "光标已在目标行 → 不动");
    }

    @Test
    void rowsToMoveReturnsZeroWhenCursorIsBelowTarget() {
        assertEquals(0, BottomAnchor.rowsToMove(45, 43),
                "光标已在目标下方 → 0 而非负数，负值会造成反向位移（终端钳位）");
    }

    @Test
    void rowsToMoveClampsDegenerateTargetToZero() {
        assertEquals(0, BottomAnchor.rowsToMove(1, 0),
                "终端过矮时 promptFirstRow 可能给出 ≤0 的行号：钳位保证退化为「不挪」而不是反向位移");
    }

    // ---- parseRow：CPR 响应解析 ----

    @Test
    void parseRowReadsLegalCprResponse() {
        assertEquals(43, BottomAnchor.parseRow("\033[43;1R"),
                "标准 CPR 响应应解析出行号（1 基）");
    }

    @Test
    void parseRowSkipsStrayLeadingBytes() {
        assertEquals(12, BottomAnchor.parseRow("abc\033[12;80R"),
                "响应前混入杂散字节仍应找到 ESC [ 并解析");
    }

    @Test
    void parseRowReadsLargeRowNumber() {
        assertEquals(999, BottomAnchor.parseRow("\033[999;1R"),
                "行号超过两位也不应被截断");
    }

    @Test
    void parseRowRejectsIncompleteResponse() {
        assertNull(BottomAnchor.parseRow("\033[43;1"),
                "缺结尾 R 的残包应返回 null（等下一个字节再试）");
    }

    @Test
    void parseRowRejectsPlainText() {
        assertNull(BottomAnchor.parseRow("hello"),
                "完全不是响应应返回 null");
    }

    @Test
    void parseRowRejectsEmptyInput() {
        assertNull(BottomAnchor.parseRow(""),
                "空串应返回 null");
    }

    @Test
    void parseRowRejectsEmptyRowParameter() {
        assertNull(BottomAnchor.parseRow("\033[;R"),
                "行号参数为空应返回 null 而不是抛异常");
    }

    @Test
    void parseRowRejectsNonNumericRowParameter() {
        assertNull(BottomAnchor.parseRow("\033[x;1R"),
                "行号参数非数字应返回 null 而不是抛异常");
    }

    @Test
    void parseRowRejectsNonPositiveRowNumber() {
        assertNull(BottomAnchor.parseRow("\033[0;1R"),
                "第 0 行不是 1 基口径下的合法行号，应返回 null");
        assertNull(BottomAnchor.parseRow("\033[-1;1R"),
                "负行号只可能来自畸形响应，应返回 null——否则 rowsToMove 会把光标反向带出屏幕");
    }
}
