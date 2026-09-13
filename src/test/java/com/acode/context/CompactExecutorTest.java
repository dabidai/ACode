package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.provider.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T5：CompactExecutor 成功重建 / 无变化 / 摘要请求形态 / 超长降级 / 熔断与守卫。 */
class CompactExecutorTest {

    private static Conversation compressibleConversation(int window, int oldTokens) {
        Conversation c = new Conversation("m", false, 4096, window);
        c.addMessage(ChatMessage.of(USER, "x".repeat(oldTokens * 4))); // 超大旧内容
        c.addMessage(ChatMessage.of(USER, "问题"));                    // 最新未答复 user
        return c;
    }

    private static CompactExecutor executor(FakeProvider provider, Conversation conversation) {
        return new CompactExecutor(provider, conversation, new ContextPolicy());
    }

    @Test
    void runSuccessRebuildsHistoryAndSummaryRequestShape() {
        Conversation c = compressibleConversation(200_000, 10_000);
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        CompactExecutor ex = executor(provider, c);
        CompactExecutor.Result r = ex.run(true);
        assertTrue(r.changed(), "manual 压缩应成功");
        assertEquals(3, c.messageCount(), "重建为 摘要(user)+边界(assistant)+未闭环步");
        assertTrue(c.history().get(0).content().contains("压缩正文"), "摘要正文入历史");
        assertTrue(r.afterEstimate() < r.beforeEstimate(), "压缩后估算应下降");
        // 摘要请求形态：不携带工具、thinking 关闭、max_tokens 用独立摘要常量
        assertEquals(1, provider.receivedRequests().size());
        var summaryRequest = provider.receivedRequests().get(0);
        assertTrue(summaryRequest.tools().isEmpty(), "摘要请求不带工具");
        assertFalse(summaryRequest.thinking(), "摘要请求 thinking 关闭");
        assertEquals(ContextPolicy.SUMMARY_MAX_TOKENS, summaryRequest.maxTokens(),
                "max_tokens 用独立摘要常量，非对话级 8192");
        assertEquals("m", summaryRequest.model());
    }

    @Test
    void runNoChangeForShortHistory() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "你好"));
        FakeProvider provider = FakeProvider.streaming("摘要");
        CompactExecutor ex = executor(provider, c);
        CompactExecutor.Result r = ex.run(true);
        assertTrue(r.noChange(), "短历史无可压缩摘要区 → noChange");
        assertEquals(1, c.messageCount(), "历史不变");
        assertTrue(provider.receivedRequests().isEmpty(), "无变化时不发摘要请求");
    }

    @Test
    void parseSummaryStripsDraftAndTags() {
        assertEquals("最终正文",
                CompactExecutor.parseSummary("<analysis>草稿内容</analysis>\n<summary>最终正文</summary>"));
        assertEquals("无标签整段兜底", CompactExecutor.parseSummary("无标签整段兜底"));
    }

    @Test
    void overlongRetryDropsOldestGroupThenSucceeds() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "a".repeat(40_000))); // 10000 token
        c.addMessage(ChatMessage.of(USER, "b".repeat(40_000))); // 10000 token
        c.addMessage(ChatMessage.of(USER, "问题"));
        // 摘要区为前两条；第 1 次超长 → 丢最旧分组重试 → 成功
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long"))),
                List.of(FakeProvider.delta("<summary>降级摘要</summary>"), FakeProvider.complete())));
        CompactExecutor ex = executor(provider, c);
        CompactExecutor.Result r = ex.run(true);
        assertTrue(r.changed(), "丢最旧分组后重试应成功");
        assertTrue(c.history().get(0).content().contains("降级摘要"));
        assertEquals(2, provider.receivedRequests().size(), "恰好两次摘要请求（初试+丢最旧重试）");
    }

    @Test
    void breakerTripsAfterThreeFailuresAndResetsOnSuccess() {
        Conversation c = compressibleConversation(70_000, 40_000);
        FakeProvider failing = FakeProvider.failing(new InvalidRequestException("参数错误"));
        CompactExecutor ex = executor(failing, c);
        // 断言未熔断时自动可触发（守卫通过、有摘要区）
        assertTrue(ex.needsAutoCompact(), "压缩前应可自动触发");
        assertTrue(ex.run(true).failed());
        assertTrue(ex.run(true).failed());
        assertTrue(ex.run(true).failed());
        assertTrue(ex.isBreakerTripped(), "连续失败 3 次 → 熔断");
        assertFalse(ex.needsAutoCompact(), "熔断后不再自动触发");
        ex.resetBreaker();
        assertFalse(ex.isBreakerTripped());
        assertTrue(ex.needsAutoCompact(), "复位熔断后可再次自动触发");
    }

    @Test
    void failedRunLeavesHistoryUntouched() {
        Conversation c = compressibleConversation(70_000, 40_000);
        int before = c.messageCount();
        List<String> beforeContents = c.history().stream().map(ChatMessage::content).toList();
        CompactExecutor ex = executor(FakeProvider.failing(new InvalidRequestException("参数错误")), c);
        assertTrue(ex.run(true).failed());
        assertEquals(before, c.messageCount(), "失败时历史不变");
        assertEquals(beforeContents, c.history().stream().map(ChatMessage::content).toList(),
                "历史逐条与失败前相同");
    }

    @Test
    void guardSkipsWhenAllHistoryFitsRetentionBudget() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "短内容"));
        FakeProvider provider = FakeProvider.streaming("摘要");
        CompactExecutor ex = executor(provider, c);
        assertFalse(ex.needsAutoCompact(), "历史全落在保留预算内 → 不触发自动压缩");
        assertTrue(provider.receivedRequests().isEmpty(), "守卫不通过则不发摘要请求");
    }

    @Test
    void guardSkipsWhenOnlyProtectedUnclosedStepExists() {
        // 单个超大的最新未答复 user（不可压缩）且估算已过触发点 → 无摘要区，needsAutoCompact 为 false
        Conversation c = new Conversation("m", false, 4096, 80_000); // 触发点 47_000
        c.addMessage(ChatMessage.of(USER, "x".repeat(200_000)));     // 50000 token，单条即 protected
        CompactExecutor ex = executor(FakeProvider.streaming("摘要"), c);
        assertTrue(c.estimateContextTokens() >= ContextPolicy.triggerPointFor(c.maxContextTokens()),
                "前提：整体估算已超触发点");
        assertFalse(ex.needsAutoCompact(), "仅剩不可压缩的未闭环步 → 不自动触发");
    }

    @Test
    void overlongDropOfIsolatedLeadingUserLeavesAssistantFirstInRetriedSummaryRequest() {
        // A3 前置触发确认：摘要区首条为孤立 user、被"丢最旧分组"丢走后，重试的摘要请求内容区以 assistant 开头。
        // 是否被 provider 拒取决于实现（Anthropic 类要求 content 首条 user；OpenAI 类容忍）——此处只确认前置可触发。
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "x".repeat(40_000)));      // 摘要区首条：孤立 user（10_000 token）
        c.addMessage(ChatMessage.of(ASSISTANT, "y".repeat(40_000))); // 摘要区次条：assistant 正文
        c.addMessage(ChatMessage.of(USER, "当前问题"));               // 未闭环步 → 保留尾
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long"))),
                List.of(FakeProvider.delta("<summary>ok</summary>"), FakeProvider.complete())));
        CompactExecutor ex = executor(provider, c);
        assertTrue(ex.run(true).changed(), "丢最旧分组后重试应成功");
        assertEquals(2, provider.receivedRequests().size(), "首次超长 → 丢最旧分组重试一次");
        assertEquals(ASSISTANT, provider.receivedRequests().get(1).messages().get(1).role(),
                "丢走孤立 user 后，重试请求 content 首条为 assistant（前置成立）");
    }

    @Test
    void runWithFocusIncludesFocusTextInSummaryInstruction() {
        Conversation c = compressibleConversation(200_000, 10_000);
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        CompactExecutor ex = executor(provider, c);
        assertTrue(ex.run(true, "数据库迁移方案").changed(), "带保留重点压缩应成功");
        String system = provider.receivedRequests().get(0).messages().get(0).content();
        assertTrue(system.contains("压缩时请特别保留：数据库迁移方案"), "摘要指令末尾含保留重点");
    }

    @Test
    void runWithoutFocusKeepsSummaryInstructionUnchanged() {
        Conversation c = compressibleConversation(200_000, 10_000);
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        CompactExecutor ex = executor(provider, c);
        assertTrue(ex.run(true).changed(), "不带保留重点压缩应成功");
        String system = provider.receivedRequests().get(0).messages().get(0).content();
        assertEquals(SummaryPrompt.instruction(), system, "不带重点时摘要指令与改动前逐字相同");
    }
}
