package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.FakeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T2/T5：ContextManager 组合门面 reset() 清空运行期状态、落盘文件不删。 */
class ContextManagerTest {

    @TempDir
    Path tempDir;

    private ContextManager manager() {
        Conversation conversation = new Conversation("m", false, 4096, 200_000);
        return new ContextManager(tempDir, FakeProvider.streaming("占位"), conversation);
    }

    @Test
    void resetClearsFreezeStateButKeepsSpilledFiles() throws Exception {
        ContextManager cm = manager();
        String big = "B".repeat(60_000);
        cm.budget().process(List.of(new ToolResultBudget.Item("spilled-1", big, false)));
        Path file = new SpillStore(tempDir).resolve("spilled-1");
        assertTrue(Files.exists(file), "落盘文件应生成");
        assertEquals(1, cm.budget().state().size(), "冻结记账应有一条");
        cm.reset();
        assertEquals(0, cm.budget().state().size(), "reset 清空冻结记账");
        assertTrue(Files.exists(file), "落盘文件不被 reset 删除（见 spec Out of Scope）");
    }
}
