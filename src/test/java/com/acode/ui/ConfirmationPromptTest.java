package com.acode.ui;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static boolean ask(StringWriter writer, int... keys) {
        return new ConfirmationPrompt(new ScriptedKeys(keys), new LiveRegionRenderer(80, 24), writer)
                .ask("WriteFile", "{\"file_path\":\"a.txt\"}");
    }

    @Test
    void defaultYesSelectionApproves() {
        StringWriter writer = new StringWriter();
        assertTrue(ask(writer, MenuKeySource.KEY_ENTER), "默认选中「是」，Enter 应批准");
        assertTrue(writer.toString().contains("（已批准执行「WriteFile」）"));
    }

    @Test
    void movingToNoThenEnterRejects() {
        StringWriter writer = new StringWriter();
        assertFalse(ask(writer, MenuKeySource.KEY_DOWN, MenuKeySource.KEY_ENTER), "↓ 后 Enter 应拒绝");
        assertTrue(writer.toString().contains("（已拒绝执行「WriteFile」）"));
    }

    @Test
    void escCancelsAsRejection() {
        StringWriter writer = new StringWriter();
        assertFalse(ask(writer, MenuKeySource.KEY_CANCEL), "Esc 应取消=拒绝");
        assertTrue(writer.toString().contains("（已取消）"));
    }

    @Test
    void promptLineOmitsYnpromptAndKeepsArgs() {
        assertEquals("要执行「WriteFile（{\"file_path\":\"a.txt\"}）」？", ConfirmationPrompt.promptLine("WriteFile", "{\"file_path\":\"a.txt\"}"));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", ""));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", null));
        assertEquals("要执行「WriteFile」？", ConfirmationPrompt.promptLine("WriteFile", "  "));
    }

    @Test
    void menuRenderedWithSelectedYesAndOptionNo() {
        StringWriter writer = new StringWriter();
        ask(writer, MenuKeySource.KEY_ENTER);
        assertTrue(writer.toString().contains("\033[7m> 是\033[0m"), "默认选中「是」应反显：" + writer);
        assertTrue(writer.toString().contains("  否"), "未选中「否」两空格前缀：" + writer);
    }

    @Test
    void promptLineAndMenuAppendAsCommitted() {
        StringWriter writer = new StringWriter();
        ask(writer, MenuKeySource.KEY_ENTER);
        assertTrue(writer.toString().contains("要执行「WriteFile（{\"file_path\":\"a.txt\"}）」？"));
        assertTrue(writer.toString().contains("（已批准执行「WriteFile」）"));
    }
}
