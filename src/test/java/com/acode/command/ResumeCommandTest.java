package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.session.Session;
import com.acode.session.SessionCodec;
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.ToolRegistry;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /resume 命令：只弹会话选择菜单，选中交 SessionManager.open 加载，无参数约束与取消路径。 */
class ResumeCommandTest {

    @TempDir
    Path tempDir;

    /** 界面操作接口测试桩：记录菜单条目与标题、返回注入的选中下标 */
    private static final class FakeUi implements UIController {
        private final List<String> messages = new ArrayList<>();
        private final List<MenuEntry> entries = new ArrayList<>();
        private String title;
        private final int selected;

        FakeUi(int selected) {
            this.selected = selected;
        }

        @Override
        public void appendSystemMessage(String text) {
            messages.add(text);
        }

        @Override
        public void submitUserInput(String text) {
        }

        @Override
        public void setPlanMode(boolean enabled) {
        }

        @Override
        public ContextUsage contextUsage() {
            return new ContextUsage(0, 0);
        }

        @Override
        public int selectMenu(List<MenuEntry> entries, String title) {
            this.entries.addAll(entries);
            this.title = title;
            return selected;
        }

        List<String> messages() {
            return messages;
        }

        List<MenuEntry> entries() {
            return entries;
        }

        String title() {
            return title;
        }
    }

    private static final String MENU_TITLE = "（↑/↓ 选择会话，回车加载，Esc 取消）";

    private List<Session> loaded = new ArrayList<>();

    private CommandContext build(String args, FakeUi ui, FakeProvider provider) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        SessionManager sessionManager = new SessionManager(SessionStore.forProject(() -> tempDir),
                conversation);
        sessionManager.setLoader(loaded::add);
        return new CommandContext(args, ui,
                new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                        new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                                tempDir.resolve("permissions.project.yaml"),
                                tempDir.resolve("permissions.local.yaml"))),
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(new MemoryStore(
                        MemoryScope.project(() -> tempDir.resolve("project")),
                        MemoryScope.user(() -> tempDir.resolve("user"))),
                        conversation, provider, false),
                sessionManager, tempDir, new ToolRegistry(), "v0.1.0");
    }

    private List<String> runResume(String args, FakeUi ui, FakeProvider provider) {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        registry.find("resume").handler().execute(build(args, ui, provider));
        return ui.messages();
    }

    private void writeSession(String id, List<ChatMessage> messages, long ts) throws IOException {
        SessionStore store = SessionStore.forProject(() -> tempDir);
        Files.createDirectories(store.dir());
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(SessionCodec.encode(message, ts)).append('\n');
        }
        Files.writeString(store.resolve(id), sb.toString());
    }

    @Test
    void resumePopsMenuAndLoadsSelectedSessionWithoutModelCall() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        writeSession("s1", List.of(
                ChatMessage.of(ChatMessage.Role.USER, "第一条问题"),
                ChatMessage.of(ChatMessage.Role.USER, "第二条问题")), now);
        FakeUi ui = new FakeUi(0);
        FakeProvider provider = FakeProvider.streaming("回答");

        List<String> messages = runResume(null, ui, provider);

        assertEquals(MENU_TITLE, ui.title(), "菜单标题应逐字一致");
        assertEquals(1, ui.entries().size(), "一个会话对应一条菜单项");
        assertTrue(ui.entries().get(0).label().contains("s1"), ui.entries().get(0).label());
        assertTrue(ui.entries().get(0).label().contains("2 条"), ui.entries().get(0).label());
        assertEquals(1, loaded.size(), "选中会话应交 loader 加载");
        assertEquals("s1", loaded.get(0).id());
        assertTrue(provider.receivedRequests().isEmpty(), "恢复菜单不应触发任何模型请求");
        assertTrue(messages.isEmpty(), "成功加载不走命令输出");
    }

    @Test
    void resumeLabelShowsExpiredMarkAndFirstUserPreview() throws IOException {
        writeSession("old", List.of(
                ChatMessage.of(ChatMessage.Role.USER, "a".repeat(40))), 1_000_000L);
        FakeUi ui = new FakeUi(-1);

        runResume(null, ui, FakeProvider.streaming("回答"));

        String label = ui.entries().get(0).label();
        assertTrue(label.contains("（已过期）"), "过期会话应带标注：" + label);
        assertTrue(label.contains("a".repeat(30) + "…"), "预览应截断到 30 字符并追加省略号：" + label);
    }

    @Test
    void resumeWithArgsShowsUsageWithoutMenuOrModelCall() {
        FakeUi ui = new FakeUi(0);
        FakeProvider provider = FakeProvider.streaming("回答");

        String output = String.join("\n", runResume("x", ui, provider));

        assertTrue(output.contains("用法：/resume（弹出会话选择菜单，不接受参数）"), output);
        assertTrue(ui.entries().isEmpty(), "带参数不应弹菜单");
        assertTrue(loaded.isEmpty(), "带参数不应加载会话");
        assertTrue(provider.receivedRequests().isEmpty());
    }

    @Test
    void resumeWithNoSessionsShowsNotice() {
        FakeUi ui = new FakeUi(0);
        String output = String.join("\n", runResume(null, ui, FakeProvider.streaming("回答")));

        assertTrue(output.contains("（没有可恢复的会话）"), output);
        assertTrue(ui.entries().isEmpty(), "无会话不应弹菜单");
    }

    @Test
    void resumeCancelLoadsNothing() throws IOException {
        writeSession("s1", List.of(ChatMessage.of(ChatMessage.Role.USER, "你好")),
                System.currentTimeMillis() / 1000);
        FakeUi ui = new FakeUi(-1);

        String output = String.join("\n", runResume(null, ui, FakeProvider.streaming("回答")));

        assertTrue(output.contains("（已取消）"), output);
        assertTrue(loaded.isEmpty(), "取消不应加载任何会话");
    }
}
