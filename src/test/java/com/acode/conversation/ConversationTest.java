package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.SYSTEM;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {

    private static final int WINDOW = 2000;

    private Conversation conversation() {
        return new Conversation("m", false, 4096, WINDOW);
    }

    private static ChatMessage user(String content) {
        return ChatMessage.of(USER, content);
    }

    @Test
    void estimateAsciiCharsToTokens() {
        assertEquals(250, Conversation.estimateTokens("a".repeat(1000)));
    }

    @Test
    void messageCountIncrements() {
        Conversation c = conversation();
        assertEquals(0, c.messageCount());
        c.addMessage(user("hi"));
        assertEquals(1, c.messageCount());
        c.addMessage(user("yo"));
        assertEquals(2, c.messageCount());
    }

    @Test
    void keepsAllMessagesWithinWindow() {
        Conversation c = conversation();
        for (int i = 0; i < 3; i++) {
            c.addMessage(user("h".repeat(400))); // 每条 100 token
        }
        c.addMessage(user("q")); // 1 token，合计 301 ≤ 2000
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(4, request.messages().size());
    }

    @Test
    void trimsOldestUntilFits() {
        Conversation c = conversation();
        for (int i = 0; i < 10; i++) {
            c.addMessage(user("h".repeat(1200))); // 每条 300 token
        }
        c.addMessage(user("q".repeat(4))); // 1 token，合计 3001 > 2000

        ChatRequest request = c.buildRequest(List.of(), null);
        int total = request.messages().stream()
                .mapToInt(m -> Conversation.estimateTokens(m.content())).sum();
        assertTrue(total <= WINDOW, "组装后总量必须 ≤ 窗口，实际 " + total);
        // 3001 → 丢 4 条（每条 300）后剩 6×300 + 1 = 1801 ≤ 2000
        assertEquals(7, request.messages().size(), "应从最早丢弃恰好 4 条，剩 7 条");
        assertEquals("q".repeat(4), request.messages().get(6).content(), "最新问题必须保留");
        assertEquals(11, c.messageCount(), "完整历史不应被截断，仍为 11 条");
    }

    @Test
    void keepsOnlyCurrentQuestionWhenItExceedsWindow() {
        Conversation c = conversation();
        c.addMessage(user("h".repeat(4000))); // 1000 token
        c.addMessage(user("big".repeat(3000))); // 9000 字符 → 2250 token，单条即超窗口
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(1, request.messages().size(), "历史全部丢弃，只保留当前问题");
        assertEquals("big".repeat(3000), request.messages().get(0).content());
    }

    @Test
    void clearEmptiesHistory() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        c.addMessage(user("yo"));
        c.clear();
        assertEquals(0, c.messageCount());
        assertTrue(c.history().isEmpty());
    }

    @Test
    void clearThenAddWorksNormally() {
        Conversation c = conversation();
        c.addMessage(user("old"));
        c.clear();
        c.addMessage(user("new"));
        assertEquals(1, c.messageCount());
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(1, request.messages().size());
        assertEquals("new", request.messages().get(0).content());
    }

    @Test
    void clearOnEmptyConversationIsSafe() {
        Conversation c = conversation();
        c.clear();
        assertEquals(0, c.messageCount());
    }

    @Test
    void estimateTokensCountsBlocksAndToolResults() {
        ObjectMapper json = new ObjectMapper();
        ChatMessage assistant = new ChatMessage(ASSISTANT, List.of(
                new TextBlock("abc"),
                new ToolUseBlock("id-1", "ReadFile",
                        json.createObjectNode().put("file_path", "a.txt"))));
        ChatMessage toolResult = new ChatMessage(USER, List.of(
                new ToolResultBlock("id-1", "你好世界", false)));

        assertTrue(Conversation.estimateTokens(assistant) > 0,
                "tool_use 块（含参数 JSON）应计入 token 估算");
        assertTrue(Conversation.estimateTokens(toolResult) > 0,
                "tool_result 块内容应计入 token 估算");
    }

    @Test
    void trimKeepsToolBlocksWhenFitsWindow() {
        ObjectMapper json = new ObjectMapper();
        Conversation c = conversation();
        c.addMessage(user("读一下文件"));
        c.addMessage(new ChatMessage(ASSISTANT, List.of(
                new TextBlock("正在读取"),
                new ToolUseBlock("id-1", "ReadFile",
                        json.createObjectNode().put("file_path", "a.txt")))));
        c.addMessage(new ChatMessage(USER, List.of(
                new ToolResultBlock("id-1", "文件内容", false))));
        c.addMessage(user("继续"));

        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(4, request.messages().size(), "窗口内应保留全部含工具块的消息");
    }

    @Test
    void addToolResultsAppendsSingleUserMessageWithBlocks() {
        Conversation c = conversation();
        c.addMessage(user("请读文件"));
        c.addToolResults(List.of(
                new ToolResultBlock("id-1", "文件内容", false),
                new ToolResultBlock("id-2", "读取失败", true)));
        assertEquals(2, c.messageCount(), "一批结果应为一条消息");
        ChatMessage last = c.history().get(1);
        assertEquals(USER, last.role());
        List<ContentBlock> blocks = last.blocks();
        assertEquals(2, blocks.size());
        ToolResultBlock first = assertInstanceOf(ToolResultBlock.class, blocks.get(0));
        assertEquals("id-1", first.toolUseId());
        assertTrue(first.isError() == false, "成功结果不应带错误标记");
        ToolResultBlock second = assertInstanceOf(ToolResultBlock.class, blocks.get(1));
        assertTrue(second.isError(), "失败结果应带错误标记");
    }

    @Test
    void systemPromptPrependedAsSystemMessageNotInHistory() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        c.setSystemPrompt("You are ACode.");
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(2, request.messages().size());
        assertEquals(SYSTEM, request.messages().get(0).role());
        assertEquals("You are ACode.", request.messages().get(0).content());
        assertEquals(1, c.history().size(), "system prompt 不进历史");
    }

    @Test
    void environmentInjectedAsFirstUserMessageNotInHistory() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        c.setEnvironment(ChatMessage.of(USER, "<system-reminder>\n# Environment\n</system-reminder>"));
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(2, request.messages().size());
        assertEquals(USER, request.messages().get(0).role());
        assertTrue(request.messages().get(0).content().contains("# Environment"));
        assertEquals(1, c.history().size(), "环境消息不进历史");
    }

    @Test
    void systemThenEnvironmentThenHistoryOrder() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        c.setSystemPrompt("SYSTEM_PROMPT");
        c.setEnvironment(ChatMessage.of(USER, "<system-reminder>\nenv\n</system-reminder>"));
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(3, request.messages().size());
        assertEquals(SYSTEM, request.messages().get(0).role());
        assertEquals(USER, request.messages().get(1).role());
        assertEquals("hi", request.messages().get(2).content());
    }

    @Test
    void turnReminderAppendedLastAndNotInHistory() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        ChatMessage reminder = ChatMessage.of(USER, "<system-reminder>\nturn\n</system-reminder>");
        ChatRequest request = c.buildRequest(List.of(), reminder);
        assertEquals(2, request.messages().size());
        assertEquals("hi", request.messages().get(0).content());
        assertEquals("<system-reminder>\nturn\n</system-reminder>", request.messages().get(1).content());
        assertEquals(1, c.history().size(), "轮次级提醒不进历史");
    }

    @Test
    void nullTurnReminderAddsNoExtraMessage() {
        Conversation c = conversation();
        c.addMessage(user("hi"));
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(1, request.messages().size());
    }

    // ---- trim 按轮删除 + 请求出口 sanitize（修复 1） ----

    private static ChatMessage assistantWithToolUse(String id, ObjectMapper json) {
        return new ChatMessage(ASSISTANT, List.of(
                new TextBlock("正在执行"),
                new ToolUseBlock(id, "ReadFile",
                        json.createObjectNode().put("file_path", "a.txt"))));
    }

    private static ChatMessage toolResultMessage(String id) {
        return new ChatMessage(USER, List.of(new ToolResultBlock(id, "结果", false)));
    }

    /** 断言请求消息里 tool_use id 与 tool_result id 双向完全一致（无孤儿） */
    private static void assertNoOrphans(List<ChatMessage> messages) {
        java.util.Set<String> useIds = new java.util.HashSet<>();
        java.util.Set<String> resultIds = new java.util.HashSet<>();
        for (ChatMessage m : messages) {
            for (ContentBlock b : m.blocks()) {
                if (b instanceof ToolUseBlock tu) {
                    useIds.add(tu.id());
                } else if (b instanceof ToolResultBlock tr) {
                    resultIds.add(tr.toolUseId());
                }
            }
        }
        assertEquals(useIds, resultIds, "tool_use 与 tool_result 必须一一配对，不能有孤儿");
    }

    @Test
    void trimDropsToolUseAndResultTogetherWhenOverWindow() {
        ObjectMapper json = new ObjectMapper();
        Conversation c = conversation();
        c.addMessage(user("读一下文件"));
        c.addMessage(assistantWithToolUse("id-1", json));
        c.addMessage(toolResultMessage("id-1"));
        for (int i = 0; i < 10; i++) {
            c.addMessage(user("h".repeat(1600))); // 每条 400 token，逼超窗
        }
        ChatRequest request = c.buildRequest(List.of(), null);
        assertNoOrphans(request.messages());
        assertEquals(13, c.messageCount(), "完整历史不应被截断");
        assertTrue(request.messages().stream().noneMatch(m ->
                m.blocks().stream().anyMatch(b ->
                        (b instanceof ToolUseBlock tu && tu.id().equals("id-1"))
                                || (b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-1")))),
                "最早的整个工具轮（id-1 的 use+result）应被整轮删除，不留一半");
    }

    @Test
    void trimRemovesWholeToolTurnsFromFrontWhenMultipleTurnsOverflow() {
        ObjectMapper json = new ObjectMapper();
        Conversation c = conversation();
        c.addMessage(user("第一轮")); // 0 token（3 字符 /4）
        c.addMessage(assistantWithToolUse("id-1", json)); // ≈ 8 token
        c.addMessage(toolResultMessage("id-1")); // 0 token
        c.addMessage(user("第二轮")); // 0 token
        c.addMessage(assistantWithToolUse("id-2", json)); // ≈ 8 token
        c.addMessage(toolResultMessage("id-2")); // 0 token
        for (int i = 0; i < 3; i++) {
            c.addMessage(user("h".repeat(1984))); // 496 token
        }
        c.addMessage(user("h".repeat(2000))); // 500 token
        // 总 ≈ 0+8+0+0+8+0+1988 = 2004 > 2000；删掉第一轮后剩 1996 ≤ 2000，停在轮边界
        ChatRequest request = c.buildRequest(List.of(), null);
        assertNoOrphans(request.messages());
        assertTrue(request.messages().stream().noneMatch(m ->
                        m.blocks().stream().anyMatch(b ->
                                (b instanceof ToolUseBlock tu && tu.id().equals("id-1"))
                                        || (b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-1")))),
                "最早的工具轮 id-1 应整轮消失");
        assertTrue(request.messages().stream().anyMatch(m ->
                        m.blocks().stream().anyMatch(b ->
                                (b instanceof ToolUseBlock tu && tu.id().equals("id-2"))
                                        || (b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-2")))),
                "较新的工具轮 id-2 应完整保留");
    }

    @Test
    void trimKeepsLatestToolTurnWhenHistoryOverflows() {
        ObjectMapper json = new ObjectMapper();
        Conversation c = conversation();
        c.addMessage(user("填充".repeat(3000))); // 750 token，最早填充
        c.addMessage(user("最新轮")); // 0 token
        c.addMessage(new ChatMessage(ASSISTANT, List.of(
                new TextBlock("x".repeat(7600)), // 1900 token 大文本
                new ToolUseBlock("id-last", "ReadFile",
                        json.createObjectNode().put("file_path", "a.txt"))))); // ≈ 7 token
        c.addMessage(toolResultMessage("id-last")); // 0 token
        // 总 ≈ 750+0+1907+0 = 2657 > 2000；删掉填充后 ≈ 1907 ≤ 2000，停在轮边界
        ChatRequest request = c.buildRequest(List.of(), null);
        assertNoOrphans(request.messages());
        assertTrue(request.messages().stream().anyMatch(m ->
                        m.blocks().stream().anyMatch(b ->
                                (b instanceof ToolUseBlock tu && tu.id().equals("id-last"))
                                        || (b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-last")))),
                "整体超窗时最新的工具轮应完整保留（不拆散）");
    }

    @Test
    void buildRequestSanitizesOrphanToolResult() {
        Conversation c = conversation();
        c.addMessage(user("读一下"));
        c.addMessage(new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("id-ok", "ReadFile",
                        new ObjectMapper().createObjectNode().put("file_path", "a.txt")))));
        c.addMessage(toolResultMessage("id-ok"));
        c.addMessage(toolResultMessage("ghost")); // 无对应 tool_use 的孤儿结果
        ChatRequest request = c.buildRequest(List.of(), null);
        assertNoOrphans(request.messages());
        assertTrue(request.messages().stream().noneMatch(m ->
                m.blocks().stream().anyMatch(b ->
                        b instanceof ToolResultBlock tr && tr.toolUseId().equals("ghost"))),
                "孤儿 tool_result（ghost）应从请求剔除，有效配对保留");
    }

    @Test
    void buildRequestSanitizesDanglingToolUse() {
        Conversation c = conversation();
        c.addMessage(user("读一下"));
        c.addMessage(new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("id-dangle", "ReadFile",
                        new ObjectMapper().createObjectNode().put("file_path", "a.txt"))))); // 无结果
        c.addMessage(user("继续"));
        ChatRequest request = c.buildRequest(List.of(), null);
        assertNoOrphans(request.messages());
        assertTrue(request.messages().stream().noneMatch(m ->
                m.blocks().stream().anyMatch(b ->
                        b instanceof ToolUseBlock tu && tu.id().equals("id-dangle"))),
                "悬空 tool_use（id-dangle）应从请求剔除");
    }

    // ---- epoch 代次 + COW 并发安全（修复 2） ----

    @Test
    void staleEpochAddMessageIsIgnored() {
        Conversation c = conversation();
        long stale = c.nextEpoch(); // epoch 1
        c.nextEpoch(); // epoch 2
        c.addMessage(stale, user("旧代次写入"));
        c.addMessage(c.currentEpoch(), user("当前代次写入"));
        assertEquals(1, c.messageCount(), "旧代次写入应被忽略");
        assertEquals("当前代次写入", c.history().get(0).content());
    }

    @Test
    void staleEpochAddToolResultsIsIgnored() {
        Conversation c = conversation();
        long stale = c.nextEpoch();
        c.nextEpoch();
        c.addToolResults(stale, List.of(new ToolResultBlock("ghost", "旧结果", false)));
        assertEquals(0, c.messageCount(), "旧代次结果写入应被忽略");
    }

    @Test
    void unconditionalAddsAlwaysAccepted() {
        Conversation c = conversation();
        c.nextEpoch();
        c.nextEpoch();
        c.addMessage(user("无条件写入"));
        c.addToolResults(List.of(new ToolResultBlock("id-1", "无条件结果", false)));
        assertEquals(2, c.messageCount(), "无条件版本不受代次限制");
    }

    @Test
    void concurrentAddsDuringBuildRequestDoNotThrow() throws InterruptedException {
        Conversation c = conversation();
        c.addMessage(user("种子消息")); // 保证 buildRequest 永不读到空历史（ChatRequest 校验非空）
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                c.addMessage(user("w" + i));
            }
        });
        writer.start();
        for (int i = 0; i < 500; i++) {
            c.buildRequest(List.of(), null); // COW 快照读，不应抛并发修改异常
        }
        writer.join();
        assertEquals(501, c.messageCount());
    }
    @Test
    void getModelReturnsInitialModel() {
        Conversation c = new Conversation("agnes-2.0-flash", false, 4096, 2000);
        assertEquals("agnes-2.0-flash", c.getModel());
    }

    @Test
    void setModelUpdatesModelUsedInBuildRequest() {
        Conversation c = new Conversation("old-model", false, 4096, 2000);
        c.setModel("new-model");
        assertEquals("new-model", c.getModel());
        c.addMessage(ChatMessage.of(com.acode.provider.ChatMessage.Role.USER, "hi"));
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals("new-model", request.model(), "buildRequest 应使用 setModel 后的新模型");
    }

    @Test
    void contextUsageFractionPrefersRealPromptTokensOverEstimate() {
        Conversation c = conversation();
        c.addMessage(user("h".repeat(400))); // 估算 100 token
        c.recordPromptTokens(1000);
        assertEquals(0.5, c.contextUsageFraction(), 1e-9, "拿到真实用量后必须用它，而不是字符估算");
    }

    @Test
    void contextUsageFractionFallsBackToEstimateIncludingSystemAndEnvironment() {
        Conversation c = conversation();
        c.setSystemPrompt("a".repeat(400)); // 100 token，不进历史但每轮都发
        c.setEnvironment(ChatMessage.of(USER, "b".repeat(400))); // 100 token，同上
        c.addMessage(user("c".repeat(400))); // 100 token
        assertEquals(0.15, c.contextUsageFraction(), 1e-9, "估算必须把 system 与环境快照算进去");
    }

    @Test
    void recordPromptTokensIgnoresNonPositiveReport() {
        Conversation c = conversation();
        c.addMessage(user("h".repeat(400))); // 估算 100 token
        c.recordPromptTokens(0);
        assertEquals(0.05, c.contextUsageFraction(), 1e-9, "代理没透传用量时应保留估算兜底");
    }

    @Test
    void contextUsageFractionClampedToOne() {
        Conversation c = conversation();
        c.recordPromptTokens(9_999_999);
        assertEquals(1.0, c.contextUsageFraction(), 1e-9);
    }

    @Test
    void contextUsageFractionZeroWhenWindowNotPositive() {
        assertEquals(0.0, new Conversation("m", false, 4096, 0).contextUsageFraction(), 1e-9);
        assertEquals(0.0, new Conversation("m", false, 4096, -1).contextUsageFraction(), 1e-9);
    }

    @Test
    void clearResetsRealPromptTokensBackToEstimate() {
        Conversation c = conversation();
        c.recordPromptTokens(1000);
        assertEquals(0.5, c.contextUsageFraction(), 1e-9);
        c.clear();
        assertEquals(0.0, c.contextUsageFraction(), 1e-9, "历史清空后页脚不该还显示清空前的占用");
    }

    @Test
    void clearKeepsSystemPromptEstimateAfterResettingRealTokens() {
        Conversation c = conversation();
        c.setSystemPrompt("a".repeat(400)); // 100 token，会话状态不随 /clear 丢失
        c.recordPromptTokens(1000);
        c.clear();
        assertEquals(0.05, c.contextUsageFraction(), 1e-9);
    }
}
