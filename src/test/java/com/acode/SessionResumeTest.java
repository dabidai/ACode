package com.acode;


import com.acode.config.AppConfig;
import com.acode.session.SessionCodec;
import com.acode.session.SessionLoader;
import com.acode.context.ContextPolicy;
import com.acode.prompt.SystemReminder;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.ui.OutputPane;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T6 恢复四步的集成验证：悬空工具调用、超长会话自动压缩、时间跨度提醒只出现一次。 */
class SessionResumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static AppConfig config(int maxContextTokens) {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(maxContextTokens);
        // 本组测试断言精确的请求次数：关掉每轮结束的异步记忆提取
        config.setMemoryAuto(false);
        return config;
    }

    private Path sessionsDir() {
        return tempDir.resolve(".acode").resolve("sessions");
    }

    private Path writeSession(String id, ChatMessage... messages) throws IOException {
        Files.createDirectories(sessionsDir());
        Path file = sessionsDir().resolve(id + ".jsonl");
        StringBuilder sb = new StringBuilder();
        long ts = System.currentTimeMillis() / 1000;
        for (ChatMessage message : messages) {
            sb.append(SessionCodec.encode(message, ts)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** 装配 resume 启动的控制器（测试无终端：注入 output 与屏幕 writer） */
    private ConversationController controller(FakeProvider provider, int maxContextTokens) {
        ConversationController controller =
                new ConversationController(provider, config(maxContextTokens), true);
        controller.setProjectRoot(tempDir);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());
        return controller;
    }

    @Test
    void recoversDanglingToolCallSoNextRequestCanBeSent() throws IOException {
        ChatMessage dangling = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("dangling", "ReadFile", JSON.createObjectNode())));
        writeSession("20260101-000000-aaaa",
                ChatMessage.of(USER, "上次的问题"), ChatMessage.of(ASSISTANT, "上次的回答"), dangling);

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("继续回答"), FakeProvider.complete())));
        ConversationController controller = controller(provider, 200_000);
        try {
            controller.restoreIfResume();
            controller.handleExchange("继续", () -> false, () -> { });

            assertEquals(1, provider.receivedRequests().size(), "恢复后应能正常发出请求");
            for (ChatMessage message : controller.conversation().history()) {
                for (ContentBlock block : message.blocks()) {
                    assertFalse(block instanceof ToolUseBlock, "恢复出的历史不应残留悬空 tool_use");
                }
            }
        } finally {
            controller.closeMcpManager();
        }
    }

    @Test
    void longRecoveredSessionIsCompactedOnce() throws IOException {
        writeSession("20260101-000000-bbbb",
                ChatMessage.of(USER, "x".repeat(240_000)), ChatMessage.of(USER, "原始问题"));

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("<summary>恢复摘要</summary>"), FakeProvider.complete()),
                List.of(FakeProvider.delta("好的"), FakeProvider.complete())));
        ConversationController controller = controller(provider, 80_000);
        try {
            controller.restoreIfResume();

            List<ChatRequest> requests = provider.receivedRequests();
            assertEquals(1, requests.size(), "恢复长会话应就地压缩一次（与 /compact 同一路径）");
            ChatRequest compact = requests.get(0);
            assertTrue(compact.tools().isEmpty(), "压缩请求不携带工具");
            assertEquals(ContextPolicy.SUMMARY_MAX_TOKENS, compact.maxTokens());

            assertTrue(controller.conversation().history().get(0).content().contains("恢复摘要"),
                    "压缩后的历史应含摘要");
        } finally {
            controller.closeMcpManager();
        }
    }

    @Test
    void staleResumeInjectsReminderOnFirstTurnOnly() throws IOException {
        long stale = System.currentTimeMillis() / 1000 - SessionLoader.STALE_SECONDS - 600;
        Files.createDirectories(sessionsDir());
        Path file = sessionsDir().resolve("20260101-000000-cccc.jsonl");
        Files.writeString(file, SessionCodec.encode(ChatMessage.of(USER, "很久以前"), stale) + "\n",
                StandardCharsets.UTF_8);

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("第一轮"), FakeProvider.complete()),
                List.of(FakeProvider.delta("第二轮"), FakeProvider.complete())));
        ConversationController controller = controller(provider, 200_000);
        try {
            controller.restoreIfResume();
            controller.handleExchange("继续", () -> false, () -> { });
            controller.handleExchange("再问", () -> false, () -> { });

            List<ChatRequest> requests = provider.receivedRequests();
            assertEquals(2, requests.size());
            assertTrue(lastMessage(requests.get(0)).content().contains("Last session activity"),
                    "恢复后首轮应带时间跨度提醒");
            assertFalse(lastMessage(requests.get(1)).content().contains("Last session activity"),
                    "第二轮不再带该提醒");
            assertTrue(controller.conversation().history().stream()
                            .noneMatch(SystemReminder::isSystemReminder),
                    "提醒不进历史");
        } finally {
            controller.closeMcpManager();
        }
    }

    @Test
    void loadingSessionDoesNotRewriteItInPlace() throws IOException {
        Path file = writeSession("20260101-000000-dddd",
                ChatMessage.of(USER, "甲"), ChatMessage.of(ASSISTANT, "乙"));
        byte[] before = Files.readAllBytes(file);

        ConversationController controller = controller(FakeProvider.scripted(List.of()), 200_000);
        try {
            controller.restoreIfResume();
            assertEquals(new String(before, StandardCharsets.UTF_8),
                    Files.readString(file), "加载期间不得把读出来的历史原地重写");
        } finally {
            controller.closeMcpManager();
        }
    }

    private static ChatMessage lastMessage(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return messages.get(messages.size() - 1);
    }
}
