package com.acode.context;

import com.acode.provider.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** ch07 T2：ToolResultBudget 阈值边界 / 同批聚合 / 冻结重放。 */
class ToolResultBudgetTest {

    @TempDir
    Path tempDir;

    private ToolResultBudget budget() {
        return new ToolResultBudget(new ContextPolicy(), new SpillStore(tempDir), new ContentReplacementState());
    }

    private static ToolResultBudget.Item item(String id, int length, boolean isError) {
        return new ToolResultBudget.Item(id, "A".repeat(length), isError);
    }

    @Test
    void exactlyAtSingleLimitKeepsFullContentWithoutSpill() {
        ToolResultBudget b = budget();
        List<ToolResultBlock> out = b.process(List.of(item("t1", 50_000, false)));
        assertEquals(50_000, out.get(0).content().length(), "恰好等于上限 → 全文保留");
        assertFalse(Files.exists(new SpillStore(tempDir).resolve("t1")), "未超上限不落盘");
    }

    @Test
    void overSingleLimitSpillsWithPreviewContainingPathAndMarker() {
        ToolResultBudget b = budget();
        List<ToolResultBlock> out = b.process(List.of(item("t1", 50_001, false)));
        ToolResultBlock block = out.get(0);
        assertTrue(block.content().length() < 50_001, "超过上限 → 预览替换");
        assertTrue(block.content().contains("tool-results") || block.content().contains("Tool"),
                "预览含保存路径");
        assertTrue(block.content().contains("…"), "预览含省略标记");
        assertTrue(Files.exists(new SpillStore(tempDir).resolve("t1")), "全文已落盘");
    }

    @Test
    void errorResultsFollowSameSpillRule() {
        ToolResultBudget b = budget();
        List<ToolResultBlock> out = b.process(List.of(item("err", 60_000, true)));
        assertTrue(out.get(0).isError(), "错误标记透传");
        assertTrue(out.get(0).content().length() < 60_000, "错误超长结果同样走落盘规则");
        assertTrue(Files.exists(new SpillStore(tempDir).resolve("err")));
    }

    @Test
    void batchAggregateSpillsLargestUntilUnderLimit() {
        ToolResultBudget b = budget();
        // 5×49_000 = 245_000 > 200_000 聚合上限；各自未超单条 50_000
        List<ToolResultBlock> out = b.process(List.of(
                item("a", 49_000, false),
                item("b", 49_000, false),
                item("c", 49_000, false),
                item("d", 49_000, false),
                item("e", 49_000, false)));
        int spilled = 0;
        long total = 0;
        for (ToolResultBlock block : out) {
            if (block.content().length() < 49_000) {
                spilled++;
            }
            total += block.content().length();
        }
        assertEquals(1, spilled, "从最大者补落盘一个后合计回落");
        assertTrue(total <= 200_000, "同批合计回落到聚合上限内，实际 " + total);
        assertEquals(49_000, out.get(1).content().length(), "未落盘者全文保留");
    }

    @Test
    void sameIdSecondPassReplaysFrozenPreviewWithoutRewrite() throws Exception {
        ToolResultBudget b = budget();
        String original = "O".repeat(60_000);
        List<ToolResultBlock> first = b.process(List.of(
                new ToolResultBudget.Item("t1", original, false)));
        Path file = new SpillStore(tempDir).resolve("t1");
        List<ToolResultBlock> second = b.process(List.of(
                new ToolResultBudget.Item("t1", "X".repeat(90_000), false)));
        assertEquals(first.get(0).content(), second.get(0).content(),
                "同 id 二次传入 → 返回同一预览串（不重新决策）");
        assertEquals(original, Files.readString(file), "不重写磁盘文件（冻结成立）");
    }

    @Test
    void persistentSpillFailureInAggregateDegradesInsteadOfSpinning() throws Exception {
        // 让工作目录下的 .acode 成为普通文件 → SpillStore 每次 createDirectories 都抛 IOException（落盘持续失败）
        Files.writeString(tempDir.resolve(".acode"), "占位（使 tool-results 无法创建）");
        ToolResultBudget b = new ToolResultBudget(new ContextPolicy(), new SpillStore(tempDir), new ContentReplacementState());
        // 3×70_000=210_000 > 200_000 触发聚合补落盘；各条又都 > 50_000 单条上限 → 全走落盘且全部失败
        List<ToolResultBudget.Item> items = List.of(
                item("a", 70_000, false), item("b", 70_000, false), item("c", 70_000, false));
        List<ToolResultBlock>[] out = new List[1];
        Thread worker = new Thread(() -> out[0] = b.process(items));
        worker.setDaemon(true); // 若死循环，断言失败后不阻塞 JVM 退出
        worker.start();
        worker.join(1_000);
        if (worker.isAlive()) {
            worker.interrupt();
            fail("落盘持续失败时 reduceAggregate 未退出——死循环（应降级保留全文入历史，而非空转）");
        }
        assertEquals(3, out[0].size(), "聚合无法回落 → 降级保留全部结果（不丢信息、不死循环）");
    }
}
