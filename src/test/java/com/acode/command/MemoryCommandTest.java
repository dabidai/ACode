package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.memory.MemoryType;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.provider.FakeProvider;
import com.acode.provider.ProviderException;
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.ToolRegistry;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /memory 命令：三层指令文件菜单、run 保留词提取、类别分组列举与取消无副作用。 */
class MemoryCommandTest {

    private static final String MENU_TITLE = "（↑/↓ 选择，回车确认，Esc 取消）";
    private static final String CREATE_ONE = """
            [{"op":"create","type":"user","name":"pref","description":"摘要","body":"正文"}]
            """;

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path fakeHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        fakeHome = tempDir.resolve("home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

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

    private MemoryStore store() {
        return new MemoryStore(MemoryScope.project(() -> tempDir.resolve("project")),
                MemoryScope.user(() -> fakeHome.resolve("user")));
    }

    private CommandContext build(String args, FakeUi ui, FakeProvider provider, MemoryStore store) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        return new CommandContext(args, ui,
                new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                        new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                                tempDir.resolve("permissions.project.yaml"),
                                tempDir.resolve("permissions.local.yaml"))),
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(store, conversation, provider, false),
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, new ToolRegistry(), "v0.1.0");
    }

    private List<String> runMemory(String args, FakeUi ui, FakeProvider provider, MemoryStore store) {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        registry.find("memory").handler().execute(build(args, ui, provider, store));
        return ui.messages();
    }

    @Test
    void memoryMenuShowsThreeLayersSeparatorAndCountWithNoSideEffects() {
        FakeUi ui = new FakeUi(-1);

        String output = String.join("\n", runMemory(null, ui, FakeProvider.streaming("回答"), store()));

        assertEquals(MENU_TITLE, ui.title(), "菜单标题应逐字一致");
        assertEquals(5, ui.entries().size(), "三层 + 分隔行 + 查看长期记忆");
        assertTrue(ui.entries().get(0).label().contains("项目指令"), ui.entries().get(0).label());
        assertTrue(ui.entries().get(0).label().contains("<项目根>/ACODE.md"), ui.entries().get(0).label());
        assertTrue(ui.entries().get(0).label().contains("未创建"), ui.entries().get(0).label());
        assertTrue(ui.entries().get(1).label().contains("本地指令"), ui.entries().get(1).label());
        assertTrue(ui.entries().get(1).label().contains("<项目根>/.acode/ACODE.md"), ui.entries().get(1).label());
        assertTrue(ui.entries().get(2).label().contains("用户指令"), ui.entries().get(2).label());
        assertTrue(ui.entries().get(2).label().contains("~/.acode/ACODE.md"), ui.entries().get(2).label());
        assertFalse(ui.entries().get(3).selectable(), "第四项应为不可选分隔行");
        assertTrue(ui.entries().get(4).label().contains("查看长期记忆（0 条）"), ui.entries().get(4).label());
        assertTrue(output.contains("（已取消）"), output);
        assertFalse(Files.exists(tempDir.resolve("ACODE.md")), "取消不应创建任何文件");
    }

    @Test
    void memoryMenuExistingLayerShowsPathLineCountAndContent() throws IOException {
        Path layer = tempDir.resolve("ACODE.md");
        Files.writeString(layer, "第一行\n第二行\n");
        FakeUi ui = new FakeUi(0);

        String output = String.join("\n", runMemory(null, ui, FakeProvider.streaming("回答"), store()));

        assertTrue(output.contains(layer + "（2 行）"), output);
        assertTrue(output.contains("第一行"), output);
        assertTrue(output.contains("第二行"), output);
    }

    @Test
    void memoryMenuMissingLayerCreatesEmptyFileWithHint() {
        FakeUi ui = new FakeUi(1);
        Path layer = tempDir.resolve(".acode").resolve("ACODE.md");

        String output = String.join("\n", runMemory(null, ui, FakeProvider.streaming("回答"), store()));

        assertTrue(output.contains(layer + "（已创建空文件）"), output);
        assertTrue(output.contains("用编辑器写入；指令文件的改动下个会话生效"), output);
        assertTrue(Files.isRegularFile(layer), "选中不存在的层应创建空文件");
        assertEquals("", contentOf(layer), "创建的文件应为空");
    }

    private static String contentOf(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void memoryRunIsReservedWordAndExtracts() {
        MemoryStore store = store();
        FakeUi ui = new FakeUi(-1);
        FakeProvider provider = FakeProvider.streaming(CREATE_ONE);

        String output = String.join("\n", runMemory("run", ui, provider, store));

        assertTrue(output.contains("记忆提取完成：新增 1 · 更新 0 · 删除 0"), output);
        assertEquals(1, store.listAll().size(), "提取结果应落盘一条记忆");
        assertEquals("pref", store.listAll().get(0).name());
        assertTrue(ui.entries().isEmpty(), "run 不应弹菜单");
    }

    @Test
    void memoryRunReservedWordIsCaseInsensitive() {
        MemoryStore store = store();
        FakeUi ui = new FakeUi(-1);

        String output = String.join("\n", runMemory("RUN", ui, FakeProvider.streaming(CREATE_ONE), store));

        assertTrue(output.contains("记忆提取完成：新增 1 · 更新 0 · 删除 0"), output);
    }

    @Test
    void memoryRunWithEmptyOpsReportsNothing() {
        FakeUi ui = new FakeUi(-1);

        String output = String.join("\n", runMemory("run", ui, FakeProvider.streaming("[]"), store()));

        assertTrue(output.contains("（没有值得记忆的内容）"), output);
    }

    @Test
    void memoryRunFailureReportsNoWrites() {
        MemoryStore store = store();
        FakeUi ui = new FakeUi(-1);

        String output = String.join("\n",
                runMemory("run", ui, FakeProvider.failing(new ProviderException("调用失败")), store));

        assertTrue(output.contains("记忆提取失败（未写入任何文件）"), output);
        assertTrue(store.listAll().isEmpty(), "失败不应写入任何记忆");
    }

    @Test
    void memoryUnknownArgShowsUsageWithoutMenuOrModelCall() {
        FakeUi ui = new FakeUi(-1);
        FakeProvider provider = FakeProvider.streaming(CREATE_ONE);

        String output = String.join("\n", runMemory("foobar", ui, provider, store()));

        assertTrue(output.contains("用法：/memory（弹出指令文件菜单）｜ /memory run（立即提取长期记忆）"), output);
        assertTrue(ui.entries().isEmpty(), "非法参数不应弹菜单");
        assertTrue(provider.receivedRequests().isEmpty(), "非法参数不应发提取请求");
    }

    @Test
    void memoryListingGroupsByCategoryProjectFirst() {
        MemoryStore store = store();
        assertTrue(store.write(MemoryType.USER, "pref", "用户偏好", "正文"));
        assertTrue(store.write(MemoryType.PROJECT, "proj-note", "项目备忘", "正文"));
        FakeUi ui = new FakeUi(4);

        String output = String.join("\n", runMemory(null, ui, FakeProvider.streaming("回答"), store));

        assertTrue(output.contains("长期记忆（2 条）"), output);
        assertTrue(output.contains("project："), output);
        assertTrue(output.contains("  proj-note — 项目备忘"), output);
        assertTrue(output.contains("user："), output);
        assertTrue(output.contains("  pref — 用户偏好"), output);
        assertTrue(output.indexOf("project：") < output.indexOf("user："), "项目级应排在用户级之前");
    }

    @Test
    void memoryListingEmptyShowsNotice() {
        FakeUi ui = new FakeUi(4);

        String output = String.join("\n", runMemory(null, ui, FakeProvider.streaming("回答"), store()));

        assertTrue(output.contains("（暂无记忆）"), output);
    }
}
