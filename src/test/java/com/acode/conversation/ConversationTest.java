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
    void keepsAllMessagesEvenWhenOverWindow() {
        // ch07：移除"组装请求时从最旧裁剪"，超窗交给压缩守卫与可见错误，历史不再静默丢
        Conversation c = conversation();
        for (int i = 0; i < 10; i++) {
            c.addMessage(user("h".repeat(1200))); // 每条 300 token
        }
        c.addMessage(user("q")); // 合计远超 2000 窗口
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(11, request.messages().size(), "buildRequest 不再裁剪，消息数与历史一致");
        assertEquals(11, c.messageCount(), "完整历史不被截断");
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
    void overWindowKeepsToolTurnsTogetherWithoutCropping() {
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
        assertEquals(13, request.messages().size(), "ch07：超窗不再裁剪，历史原样带出");
        assertEquals(13, c.messageCount(), "完整历史不被截断");
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
}
