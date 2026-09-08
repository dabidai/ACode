package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T4：CompactionPlanner 分区与重建（预算装填 / 未闭环步逐字保留 / 不变量）。 */
class CompactionPlannerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompactionPlanner planner = new CompactionPlanner(new ContextPolicy());

    private static ChatMessage bigUser(String text, int tokens) {
        return ChatMessage.of(USER, text.repeat(tokens * 4)); // 字符÷4 ≈ tokens
    }

    @Test
    void historyWithinTailBudgetIsNotCompressible() {
        List<ChatMessage> small = List.of(
                ChatMessage.of(USER, "你好"),
                ChatMessage.of(ASSISTANT, "你好，有什么可以帮你？"));
        CompactionPlanner.Partition plan = planner.plan(small);
        assertFalse(plan.compressible(), "预算内无摘要区 → 不可压缩");
        assertEquals(0, plan.cutIndex());
    }

    @Test
    void compressibleWhenOlderContentExceedsBudgetAndFreshUserKept() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(bigUser("x", 10_000)); // 超大旧内容
        history.add(ChatMessage.of(USER, "当前问题")); // 最新未答复 user
        CompactionPlanner.Partition plan = planner.plan(history);
        assertTrue(plan.compressible());
        List<ChatMessage> tail = planner.retainedTail(history, plan);
        assertEquals(1, tail.size(), "保留尾装不下旧大轮次 → 只留未闭环步");
        assertEquals("当前问题", tail.get(0).content(), "未闭环步（最新未答复 user）逐字保留");
        assertEquals(1, planner.summaryRegion(history, plan).size(), "旧大轮次整体进摘要区");
    }

    @Test
    void rebuildUserLedSeamAddsIndependentBoundaryReminder() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(bigUser("x", 10_000));
        history.add(ChatMessage.of(USER, "新问题"));
        CompactionPlanner.Partition plan = planner.plan(history);
        List<ChatMessage> rebuilt = planner.rebuild(history, plan, "压缩摘要");
        assertEquals(3, rebuilt.size());
        assertEquals(USER, rebuilt.get(0).role());
        assertTrue(rebuilt.get(0).content().startsWith("压缩摘要"));
        assertEquals(ASSISTANT, rebuilt.get(1).role(), "独立 assistant 边界提醒");
        assertTrue(rebuilt.get(1).content().contains("重新读取"), "边界含重新读取指引");
        assertEquals(USER, rebuilt.get(2).role());
        assertEquals("新问题", rebuilt.get(2).content());
        assertNoSameRoleAdjacent(rebuilt);
    }

    @Test
    void rebuildAssistantLedSeamMergesHintIntoSummaryUser() {
        // 保留尾以 assistant(tool_use) 开头：边界提醒并入摘要 user，防同角色相邻
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.of(USER, "开始"));
        history.add(new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("t1", "ReadFile", JSON.createObjectNode().put("file_path", "a.txt")))));
        history.add(new ChatMessage(USER, List.of(new ToolResultBlock("t1", "文件内容", false))));
        CompactionPlanner.Partition plan = new CompactionPlanner.Partition(1, true);
        List<ChatMessage> rebuilt = planner.rebuild(history, plan, "摘要正文");
        assertEquals(3, rebuilt.size());
        assertEquals(USER, rebuilt.get(0).role());
        assertTrue(rebuilt.get(0).content().contains("摘要正文"), "摘要正文保留");
        assertTrue(rebuilt.get(0).content().contains("重新读取"), "边界提醒并入摘要 user 文本");
        assertEquals(ASSISTANT, rebuilt.get(1).role());
        assertNoSameRoleAdjacent(rebuilt);
        assertNoOrphans(rebuilt);
    }

    @Test
    void rebuildPassesThroughConversationSanitizeWithoutOrphans() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(bigUser("x", 10_000));
        history.add(ChatMessage.of(USER, "问题"));
        CompactionPlanner.Partition plan = planner.plan(history);
        List<ChatMessage> rebuilt = planner.rebuild(history, plan, "摘要");
        List<ChatMessage> cleaned = Conversation.sanitize(new ArrayList<>(rebuilt));
        assertEquals(cleaned.size(), rebuilt.size(), "重建结果经 sanitize 无孤儿（无需再删）");
        assertNoOrphans(rebuilt);
    }

    @Test
    void partitionKeepsToolRoundPairTogetherSoRebuildHasNoOrphans() {
        // 工具轮（assistant tool_use + tool_result user）作为单元整体处理，不拆半进摘要
        List<ChatMessage> history = new ArrayList<>();
        history.add(bigUser("old", 10_000)); // 旧文本超预算
        history.add(new ChatMessage(ASSISTANT, List.of(
                new TextBlock("正在读取"),
                new ToolUseBlock("t2", "ReadFile", JSON.createObjectNode().put("file_path", "b.txt")))));
        history.add(new ChatMessage(USER, List.of(new ToolResultBlock("t2", "内容".repeat(20), false))));
        history.add(ChatMessage.of(USER, "继续")); // 最新未答复 user，protected
        CompactionPlanner.Partition plan = planner.plan(history);
        assertTrue(plan.compressible());
        List<ChatMessage> rebuilt = planner.rebuild(history, plan, "摘要");
        assertNoOrphans(rebuilt, "重建后无孤儿工具块（配对被保留或整体被摘）");
    }

    private static void assertNoSameRoleAdjacent(List<ChatMessage> messages) {
        for (int i = 1; i < messages.size(); i++) {
            assertFalse(messages.get(i).role() == messages.get(i - 1).role(),
                    "重建后不应出现同角色相邻：" + messages.get(i - 1).role() + "→" + messages.get(i).role());
        }
    }

    private static void assertNoOrphans(List<ChatMessage> messages) {
        assertNoOrphans(messages, "tool_use 与 tool_result 应一一配对");
    }

    private static void assertNoOrphans(List<ChatMessage> messages, String message) {
        Set<String> useIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage m : messages) {
            for (ContentBlock b : m.blocks()) {
                if (b instanceof ToolUseBlock tu) {
                    useIds.add(tu.id());
                } else if (b instanceof ToolResultBlock tr) {
                    resultIds.add(tr.toolUseId());
                }
            }
        }
        assertEquals(useIds, resultIds, message);
    }
}
