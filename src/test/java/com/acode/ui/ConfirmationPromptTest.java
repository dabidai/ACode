package com.acode.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acode.permission.PermissionResponse;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class ConfirmationPromptTest {

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

    private static PermissionResponse ask(StringWriter writer, int... keys) {
        return new ConfirmationPrompt(new ScriptedKeys(keys), new LiveRegionRenderer(80, 24), writer)
                .ask("WriteFile", "{\"file_path\":\"a.txt\"}");
    }

    @Test
    void defaultSelectionEnterApproves() {
        StringWriter writer = new StringWriter();
        assertEquals(PermissionResponse.ALLOW, ask(writer, MenuKeySource.KEY_ENTER), "默认选中「放行」，Enter 应放行");
        assertTrue(writer.toString().contains("（已批准执行「WriteFile」）"));
    }

    @Test
    void movingToAlwaysAllowThenEnterRecordsAlways() {
        StringWriter writer = new StringWriter();
        assertEquals(PermissionResponse.ALLOW_ALWAYS,
                ask(writer, MenuKeySource.KEY_DOWN, MenuKeySource.KEY_ENTER), "↓ 后 Enter 应「始终允许」");
        assertTrue(writer.toString().contains("（已记录「始终允许」"));
    }

    @Test
    void movingToRejectThenEnterRejects() {
        StringWriter writer = new StringWriter();
        assertEquals(PermissionResponse.DENY,
                ask(writer, MenuKeySource.KEY_DOWN, MenuKeySource.KEY_DOWN, MenuKeySource.KEY_ENTER),
                "↓↓ 后 Enter 应拒绝");
        assertTrue(writer.toString().contains("（已拒绝执行「WriteFile」）"));
    }

    @Test
    void escCancelsAsRejection() {
        StringWriter writer = new StringWriter();
        assertEquals(PermissionResponse.DENY, ask(writer, MenuKeySource.KEY_CANCEL), "Esc 应取消=拒绝");
        assertTrue(writer.toString().contains("（已取消）"));
    }

    @Test
    void promptLineOmitsArgsWhenEmpty() {
        assertEquals("要执行「WriteFile（{\"file_path\":\"a.txt\"}）」？", ConfirmationPrompt.promptLine("WriteFile", "{\"file_path\":\"a.txt\"}"));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", ""));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", null));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", "  "));
    }

    @Test
    void menuRenderedWithThreeOptions() {
        StringWriter writer = new StringWriter();
        ask(writer, MenuKeySource.KEY_ENTER);
        assertTrue(writer.toString().contains("\033[7m> 放行\033[0m"), "默认选中「放行」应反显：" + writer);
        assertTrue(writer.toString().contains("始终允许"), "菜单应含「始终允许」：" + writer);
        assertTrue(writer.toString().contains("拒绝"), "菜单应含「拒绝」：" + writer);
    }

    @Test
    void promptLineAndMenuAppendAsCommitted() {
        StringWriter writer = new StringWriter();
        ask(writer, MenuKeySource.KEY_ENTER);
        assertTrue(writer.toString().contains("要执行「WriteFile（{\"file_path\":\"a.txt\"}）」？"));
        assertTrue(writer.toString().contains("（已批准执行「WriteFile」）"));
    }
}
