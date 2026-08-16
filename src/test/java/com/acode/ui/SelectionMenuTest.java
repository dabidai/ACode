package com.acode.ui;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionMenuTest {

    /** 脚本化语义键队列的按键源；耗尽即测试失败（菜单应已退出）。 */
    static class ScriptedKeys implements MenuKeySource {
        private final ArrayDeque<Integer> keys = new ArrayDeque<>();

        ScriptedKeys(int... ks) {
            for (int k : ks) {
                keys.add(k);
            }
        }

        @Override
        public int readKey() {
            if (keys.isEmpty()) {
                throw new AssertionError("按键序列耗尽，菜单未退出");
            }
            return keys.poll();
        }
    }

    private static int select(Writer writer, LiveRegionRenderer live, int... keys) {
        SelectionMenu menu = new SelectionMenu(java.util.List.of("A", "B", "C"), "（测试菜单）", 0);
        return menu.select(live, writer, new ScriptedKeys(keys));
    }

    private static LiveRegionRenderer live() {
        return new LiveRegionRenderer(80, 24);
    }

    @Test
    void enterReturnsInitialSelectedIndex() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer live = live();
        assertEquals(0, select(sw, live, MenuKeySource.KEY_ENTER));
        assertTrue(sw.toString().contains("\033[7m> A\033[0m"), "选中行反显 + > 箭头：" + sw);
        assertTrue(sw.toString().contains("  B"), "未选中行两空格前缀：" + sw);
        assertTrue(sw.toString().contains("（测试菜单）"), "header 为首行");
    }

    @Test
    void downMovesSelection() {
        StringWriter sw = new StringWriter();
        assertEquals(1, select(sw, live(), MenuKeySource.KEY_DOWN, MenuKeySource.KEY_ENTER));
    }

    @Test
    void upWrapsAroundToLastOption() {
        StringWriter sw = new StringWriter();
        assertEquals(2, select(sw, live(), MenuKeySource.KEY_UP, MenuKeySource.KEY_ENTER), "首项↑应回绕到末项");
    }

    @Test
    void downWrapsAroundToFirstOption() {
        StringWriter sw = new StringWriter();
        assertEquals(0, select(sw, live(),
                MenuKeySource.KEY_DOWN, MenuKeySource.KEY_DOWN, MenuKeySource.KEY_DOWN,
                MenuKeySource.KEY_ENTER), "末项↓应回绕到首项");
    }

    @Test
    void escReturnsCancel() {
        StringWriter sw = new StringWriter();
        assertEquals(-1, select(sw, live(), MenuKeySource.KEY_CANCEL));
    }

    @Test
    void noneKeysAreIgnoredAndMenuStays() {
        StringWriter sw = new StringWriter();
        assertEquals(0, select(sw, live(), MenuKeySource.KEY_NONE, MenuKeySource.KEY_NONE, MenuKeySource.KEY_ENTER),
                "无关键应被忽略，菜单保持可继续选择");
    }

    @Test
    void menuClearsRegionOnEnterAndCancel() {
        StringWriter sw = new StringWriter();
        LiveRegionRenderer live = live();
        select(sw, live, MenuKeySource.KEY_ENTER);
        assertEquals(0, live.rowsWritten(), "Enter 退出后活跃区应清零");

        StringWriter sw2 = new StringWriter();
        LiveRegionRenderer live2 = live();
        select(sw2, live2, MenuKeySource.KEY_CANCEL);
        assertEquals(0, live2.rowsWritten(), "Esc 退出后活跃区应清零");
    }

    @Test
    void headerCanBeNull() {
        StringWriter sw = new StringWriter();
        SelectionMenu menu = new SelectionMenu(java.util.List.of("A", "B"), null, 0);
        assertEquals(1, menu.select(live(), sw, new ScriptedKeys(MenuKeySource.KEY_DOWN, MenuKeySource.KEY_ENTER)));
        assertTrue(sw.toString().contains("\033[7m> B\033[0m"), "无 header 时选中行直接渲染选项");
        assertTrue(sw.toString().contains("  A"), "未选中行渲染选项");
    }
}
