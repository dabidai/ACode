package com.acode.session;

import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    private static final long NOW = 1_800_000_000L;

    private static Conversation conversation() {
        return new Conversation("m", false, 4096, 2000);
    }

    private SessionStore store() {
        return new SessionStore(tempDir, () -> NOW);
    }

    private SessionManager manager(Conversation conversation, OutputPane output) {
        SessionManager manager = new SessionManager(store(), conversation);
        manager.attachUi(output, new RenderContext(new AppConfig()), null);
        return manager;
    }

    private static Session session(String id, long lastActive, boolean expired, String... contents) {
        List<ChatMessage> messages = List.of(contents).stream()
                .map(text -> ChatMessage.of(USER, text))
                .toList();
        return new Session(id, lastActive, messages, expired);
    }

    @Test
    void messagesAreWrittenToDiskAsTheyArrive() throws IOException {
        Conversation conversation = conversation();
        manager(conversation, new OutputPane());

        assertNull(sessionFiles(), "空会话不应写出任何文件");

        conversation.addMessage(ChatMessage.of(USER, "第一问"));
        conversation.addMessage(ChatMessage.of(ASSISTANT, "第一答"));

        Path file = sessionFiles();
        assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size(),
                "消息一进历史就应逐条落盘");
    }

    @Test
    void toolResultMessagesAreAlsoPersisted() throws IOException {
        Conversation conversation = conversation();
        manager(conversation, new OutputPane());

        conversation.addMessage(ChatMessage.of(USER, "问"));
        conversation.addToolResults(List.of(
                new com.acode.provider.ToolResultBlock("u1", "结果", false)));

        assertEquals(2, Files.readAllLines(sessionFiles(), StandardCharsets.UTF_8).size());
    }

    @Test
    void rebuildListenerRewritesTheWholeFile() throws IOException {
        Conversation conversation = conversation();
        manager(conversation, new OutputPane());
        conversation.addMessage(ChatMessage.of(USER, "旧一"));
        conversation.addMessage(ChatMessage.of(USER, "旧二"));

        conversation.replaceAll(List.of(ChatMessage.of(USER, "重建")));

        assertEquals(List.of("重建"), SessionStore.readEntries(sessionFiles()).stream()
                .map(e -> e.message().content()).toList());
    }

    @Test
    void closeSessionIsIdempotentAndKeepsFile() throws IOException {
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, new OutputPane());
        conversation.addMessage(ChatMessage.of(USER, "一"));

        manager.closeSession();
        manager.closeSession();

        assertEquals(1, Files.readAllLines(sessionFiles(), StandardCharsets.UTF_8).size(),
                "关闭句柄不应丢内容、也不应新建文件");
    }

    @Test
    void diskWriteFailureKeepsMessagesInMemoryAndDoesNotThrow() throws IOException {
        // 让 <项目根>/.acode 是普通文件：会话目录建不出来，落盘必然失败
        Path blockedRoot = tempDir.resolve("blocked-proj");
        Files.createDirectories(blockedRoot);
        Files.writeString(blockedRoot.resolve(".acode"), "not a directory");
        Conversation conversation = conversation();
        SessionManager manager = new SessionManager(
                new SessionStore(blockedRoot, () -> NOW), conversation);

        conversation.addMessage(ChatMessage.of(USER, "写盘失败也不能丢"));
        conversation.addMessage(ChatMessage.of(ASSISTANT, "仍应在内存历史里"));

        assertEquals(List.of("写盘失败也不能丢", "仍应在内存历史里"),
                conversation.history().stream().map(ChatMessage::content).toList(),
                "写盘失败只记告警，内存历史仍更新（不丢消息、不中断对话）");
        assertNull(manager.recorder().file(), "写盘失败不应留下半成品指向");
        assertFalse(Files.isDirectory(blockedRoot.resolve(".acode").resolve("sessions")));
    }

    @Test
    void selectWithoutSessionsPrintsExistingNotice() {
        OutputPane output = new OutputPane();
        manager(conversation(), output).selectSession();
        assertTrue(output.lines().contains("（没有可恢复的会话）"));
    }

    @Test
    void openDelegatesToInjectedLoader() {
        SessionManager manager = manager(conversation(), new OutputPane());
        Session[] captured = new Session[1];
        manager.setLoader(session -> captured[0] = session);
        Session target = session("20260101-000000-abcd", NOW, false, "内容");

        manager.open(target);

        assertSame(target, captured[0], "选中会话应交给注入的加载动作");
    }

    @Test
    void entryLabelsMarkExpiredSessions() {
        List<String> labels = SessionManager.entryLabels(List.of(
                session("20260101-000000-abcd", NOW, false, "活跃会话内容"),
                session("20260201-000000-bbbb", NOW, true, "过期会话内容")));

        assertEquals(2, labels.size());
        assertTrue(labels.get(0).startsWith("20260101-000000-abcd  1 条 · 活跃会话内容"), labels.get(0));
        assertTrue(labels.get(1).contains("（已过期）"), labels.get(1));
        assertFalse(labels.get(0).contains("（已过期）"));
    }

    @Test
    void previewFallsBackWhenNoUserMessage() {
        List<String> labels = SessionManager.entryLabels(List.of(
                new Session("20260101-000000-abcd", NOW,
                        List.of(ChatMessage.of(ASSISTANT, "只有助手消息")), false)));
        assertTrue(labels.get(0).contains("（无用户消息）"), labels.get(0));
    }

    private Path sessionFiles() throws IOException {
        Path dir = store().dir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .findFirst().orElse(null);
        }
    }
}
