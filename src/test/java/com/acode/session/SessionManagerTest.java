package com.acode.session;

import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    private static Conversation conversation() {
        return new Conversation("m", false, 4096, 2000);
    }

    private SessionStore store() {
        return new SessionStore(tempDir);
    }

    private static ChatMessage user(String content) {
        return ChatMessage.of(USER, content);
    }

    private static ChatMessage assistant(String content) {
        return ChatMessage.of(ASSISTANT, content);
    }

    /** 装配 manager：tui 传 null（本组测试不触碰终端交互路径）。 */
    private SessionManager manager(Conversation conversation, OutputPane output, RenderContext rc) {
        SessionManager manager = new SessionManager(store(), conversation);
        manager.attachUi(output, rc, null);
        return manager;
    }

    @Test
    void saveSessionSkipsEmptyConversation() {
        SessionManager manager = new SessionManager(store(), conversation());
        manager.saveSession();
        assertEquals(0, countJsonFiles(), "空会话不应写出任何文件");
    }

    @Test
    void saveSessionWritesNonEmptyHistory() {
        Conversation conversation = conversation();
        conversation.addMessage(user("第一问"));
        conversation.addMessage(assistant("第一答"));
        SessionManager manager = new SessionManager(store(), conversation);
        manager.saveSession();

        assertEquals(1, countJsonFiles(), "非空会话应写出恰好 1 个会话文件");
        Session saved = store().readLatest().orElseThrow();
        assertEquals(List.of("第一问", "第一答"),
                saved.getMessages().stream().map(ChatMessage::content).toList(),
                "落盘内容应为全部历史消息且顺序不变");
    }

    @Test
    void restoreIfResumeFalseDoesNothing() {
        OutputPane output = new OutputPane();
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, output, new RenderContext(new AppConfig()));

        manager.restoreIfResume(false);

        assertEquals(0, output.lineCount(), "resume=false 时不应输出任何内容");
        assertEquals(0, conversation.messageCount(), "resume=false 时不应改动会话");
    }

    @Test
    void restoreIfResumeTrueWithoutSessionsPrintsNotice() {
        OutputPane output = new OutputPane();
        SessionManager manager = manager(conversation(), output, new RenderContext(new AppConfig()));

        manager.restoreIfResume(true);

        assertTrue(output.lines().contains("（没有可恢复的会话）"),
                "无可恢复会话时应提示「没有可恢复的会话」");
    }

    @Test
    void restoreIfResumeTrueRestoresMessagesAndBanner() {
        store().save(new Session(null, System.currentTimeMillis(),
                List.of(user("旧提问"), assistant("旧回答"))));
        OutputPane output = new OutputPane();
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, output, new RenderContext(new AppConfig()));

        manager.restoreIfResume(true);

        assertEquals(2, conversation.messageCount(), "恢复后会话应包含全部消息");
        assertEquals("旧提问", conversation.history().get(0).content(), "恢复顺序应保持");
        assertTrue(output.lines().stream().anyMatch(l -> l.startsWith("● ") && l.contains("旧提问")),
                "恢复后用户消息应渲染为带 ● 前缀（role 已正确序列化往返）");
        assertTrue(output.lines().contains("旧回答"),
                "恢复后应输出 assistant 消息内容（无前缀）");
        assertTrue(output.lines().stream().anyMatch(l -> l.startsWith("（已恢复会话 ")),
                "恢复后应输出「已恢复会话」提示行");
    }

    @Test
    void loadSessionReplacesConversation() {
        Conversation conversation = conversation();
        conversation.addMessage(user("旧历史"));
        OutputPane output = new OutputPane();
        SessionManager manager = manager(conversation, output, new RenderContext(new AppConfig()));

        Session session = new Session("target", 1L, List.of(user("新提问"), assistant("新回答")));
        manager.loadSession(session);

        assertEquals(2, conversation.messageCount(), "加载后会话应替换为会话内容");
        assertEquals("新提问", conversation.history().get(0).content(), "加载后首条应为新会话消息");
        assertTrue(output.lines().stream().anyMatch(l -> l.startsWith("（已加载会话 target")),
                "加载后应输出「已加载会话」提示行");
    }

    @Test
    void selectSessionWithoutSessionsPrintsNotice() {
        OutputPane output = new OutputPane();
        SessionManager manager = manager(conversation(), output, new RenderContext(new AppConfig()));

        manager.selectSession();

        assertTrue(output.lines().contains("（没有可恢复的会话）"),
                "无历史会话时选择菜单应提示「没有可恢复的会话」");
    }

    @Test
    void saveSessionAfterVerbatimReloadSkipsDuplicate() {
        SessionStore store = store();
        store.save(new Session(null, System.currentTimeMillis(),
                List.of(user("旧问"), assistant("旧答"))));
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, new OutputPane(), new RenderContext(new AppConfig()));

        manager.loadSession(store.readLatest().orElseThrow());
        assertEquals(2, conversation.messageCount(), "加载后会话应包含全部历史");

        manager.saveSession();

        assertEquals(1, countJsonFiles(), "resume 后未新增消息直接退出，不应再存一份重复会话");
    }

    @Test
    void saveSessionAfterRestoreWithoutNewMessagesSkipsDuplicate() {
        SessionStore store = store();
        store.save(new Session(null, System.currentTimeMillis(),
                List.of(user("旧问"), assistant("旧答"))));
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, new OutputPane(), new RenderContext(new AppConfig()));

        manager.restoreIfResume(true);
        assertEquals(2, conversation.messageCount());

        manager.saveSession();

        assertEquals(1, countJsonFiles(), "--resume 启动后未新增消息直接退出，不应重复存档");
    }

    @Test
    void saveSessionAfterReloadWithNewMessagesSavesContinuation() {
        SessionStore store = store();
        store.save(new Session(null, System.currentTimeMillis(),
                List.of(user("旧问"), assistant("旧答"))));
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, new OutputPane(), new RenderContext(new AppConfig()));
        manager.loadSession(store.readLatest().orElseThrow());
        conversation.addMessage(user("追问"));

        manager.saveSession();

        assertEquals(2, countJsonFiles(), "resume 后新增消息，退出应另存续写会话");
        assertEquals(List.of("旧问", "旧答", "追问"),
                store.readLatest().orElseThrow().getMessages().stream().map(ChatMessage::content).toList(),
                "续写文件应包含加载历史 + 新增消息");
    }

    @Test
    void saveSessionAfterReloadClearAndRechatSameCountSavesNewFile() {
        SessionStore store = store();
        store.save(new Session(null, System.currentTimeMillis(), List.of(user("旧问"))));
        Conversation conversation = conversation();
        SessionManager manager = manager(conversation, new OutputPane(), new RenderContext(new AppConfig()));
        manager.loadSession(store.readLatest().orElseThrow());
        conversation.clear();
        conversation.addMessage(user("新问"));

        manager.saveSession();

        assertEquals(2, countJsonFiles(), "/clear 后重聊且消息数恰好相同的新对话应正常存档，不能按数量误判为重复");
        assertEquals("新问", store.readLatest().orElseThrow().getMessages().get(0).content(),
                "新存档应是重聊后的内容而非加载的旧会话");
    }

    private int countJsonFiles() {
        try (var stream = Files.list(tempDir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
