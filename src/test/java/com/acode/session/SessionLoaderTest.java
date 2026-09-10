package com.acode.session;

import com.acode.provider.ChatMessage;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLoaderTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_800_000_000L;

    private Path write(String... lines) throws IOException {
        Path file = tempDir.resolve("20260101-000000-abcd.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String line(ChatMessage message, long ts) {
        return SessionCodec.encode(message, ts);
    }

    @Test
    void skipsBadLinesAndKeepsTheRest() throws IOException {
        Path file = write(line(ChatMessage.of(USER, "甲"), NOW - 100), "{坏行",
                line(ChatMessage.of(ASSISTANT, "乙"), NOW - 50));
        SessionLoader.Loaded loaded = SessionLoader.load(file, NOW);
        assertEquals(List.of("甲", "乙"),
                loaded.messages().stream().map(ChatMessage::content).toList());
    }

    @Test
    void keepsCompleteHistoryUntouched() throws IOException {
        ChatMessage use = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u1", "ReadFile", JSON.createObjectNode())));
        ChatMessage result = new ChatMessage(USER, List.of(new ToolResultBlock("u1", "内容", false)));
        Path file = write(line(ChatMessage.of(USER, "问"), NOW - 30), line(use, NOW - 20),
                line(result, NOW - 10));

        assertEquals(3, SessionLoader.load(file, NOW).messages().size(),
                "工具调用与结果都配对时不应截断");
    }

    @Test
    void truncatesTrailingDanglingToolUse() throws IOException {
        ChatMessage use1 = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u1", "ReadFile", JSON.createObjectNode())));
        ChatMessage result1 = new ChatMessage(USER, List.of(new ToolResultBlock("u1", "内容", false)));
        ChatMessage use2 = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u2", "Grep", JSON.createObjectNode())));

        Path file = write(line(ChatMessage.of(USER, "问"), NOW - 40), line(use1, NOW - 30),
                line(result1, NOW - 20), line(use2, NOW - 10));

        List<ChatMessage> messages = SessionLoader.load(file, NOW).messages();
        assertEquals(3, messages.size(), "悬空 tool_use 与其后的残缺对整段丢弃");
        assertTrue(messages.get(2).blocks().stream().anyMatch(b -> b instanceof ToolResultBlock));
    }

    @Test
    void truncatesToLastCompletePair() throws IOException {
        ChatMessage use1 = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u1", "ReadFile", JSON.createObjectNode())));
        ChatMessage result1 = new ChatMessage(USER, List.of(new ToolResultBlock("u1", "内容", false)));
        ChatMessage use2 = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u2", "Grep", JSON.createObjectNode())));
        ChatMessage result2 = new ChatMessage(USER, List.of(new ToolResultBlock("u2", "结果", false)));
        ChatMessage use3 = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u3", "Glob", JSON.createObjectNode())));

        Path file = write(line(use1, NOW - 60), line(result1, NOW - 50), line(use2, NOW - 40),
                line(result2, NOW - 30), line(use3, NOW - 20));

        assertEquals(4, SessionLoader.load(file, NOW).messages().size(),
                "截断位置取最后一个全部配对处，而非最后一条消息");
    }

    @Test
    void producesReminderOnlyWhenLastActivityIsStale() throws IOException {
        Path stale = write(line(ChatMessage.of(USER, "久远"),
                NOW - SessionLoader.STALE_SECONDS - 3600));
        String reminder = SessionLoader.load(stale, NOW).staleReminder().orElseThrow();
        assertTrue(reminder.contains("Last session activity"), reminder);
        assertTrue(reminder.toLowerCase().contains("re-read"), reminder);

        Path recent = write(line(ChatMessage.of(USER, "刚刚"), NOW - 3600));
        assertTrue(SessionLoader.load(recent, NOW).staleReminder().isEmpty(),
                "未超阈值不产出提醒");
    }

    @Test
    void emptyFileLoadsAsEmptySession() throws IOException {
        Path file = write(); // 0 字节（进程被杀 / 从未写入）
        assertTrue(Files.size(file) == 0);

        SessionLoader.Loaded loaded = SessionLoader.load(file, NOW);

        assertTrue(loaded.messages().isEmpty());
        assertEquals(0L, loaded.lastActiveEpochSeconds());
        assertTrue(loaded.staleReminder().isEmpty());
        assertTrue(SessionStore.readEntries(file).isEmpty());
    }

    @Test
    void staleThresholdMatchesTheDocumentedTwentyFourHours() {
        assertEquals(86_400L, SessionLoader.STALE_SECONDS);
    }

    @Test
    void missingOrEmptyFileLoadsAsEmptySession() {
        SessionLoader.Loaded missing = SessionLoader.load(tempDir.resolve("nope.jsonl"), NOW);
        assertTrue(missing.messages().isEmpty());
        assertEquals(0L, missing.lastActiveEpochSeconds());
        assertTrue(missing.staleReminder().isEmpty());
    }

    @Test
    void allBadLinesLoadAsEmptySession() throws IOException {
        Path file = write("{坏", "也坏");
        assertTrue(SessionLoader.load(file, NOW).messages().isEmpty());
    }

    @Test
    void lastActiveComesFromFinalLineEvenWhenTruncated() throws IOException {
        ChatMessage use = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("u9", "ReadFile", JSON.createObjectNode())));
        Path file = write(line(ChatMessage.of(USER, "问"), NOW - 100), line(use, NOW - 5));
        SessionLoader.Loaded loaded = SessionLoader.load(file, NOW);
        assertEquals(1, loaded.messages().size());
        assertEquals(NOW - 5, loaded.lastActiveEpochSeconds(), "最后活跃取文件末行时间戳");
        assertFalse(loaded.staleReminder().isPresent());
    }
}
