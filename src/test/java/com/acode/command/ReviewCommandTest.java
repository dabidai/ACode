package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.provider.FakeProvider;
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.ToolRegistry;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /review 提示词命令：只构造预设审查提示词经对话通道发出，不在本地产生任何输出 */
class ReviewCommandTest {

    private static final String VERSION = "v0.1.0";

    @TempDir
    Path tempDir;

    /** 界面操作接口测试桩：收集系统消息与提交的用户输入 */
    private static final class FakeUi implements UIController {
        private final List<String> messages = new ArrayList<>();
        private final List<String> submitted = new ArrayList<>();

        @Override
        public void appendSystemMessage(String text) {
            messages.add(text);
        }

        @Override
        public void submitUserInput(String text) {
            submitted.add(text);
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
            return -1;
        }
    }

    private CommandContext build(String args, FakeUi ui) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        FakeProvider provider = FakeProvider.streaming("hi");
        MemoryStore store = new MemoryStore(MemoryScope.project(() -> tempDir.resolve("project")),
                MemoryScope.user(() -> tempDir.resolve("user")));
        return new CommandContext(args, ui,
                new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                        new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                                tempDir.resolve("permissions.project.yaml"),
                                tempDir.resolve("permissions.local.yaml"))),
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(store, conversation, provider, false),
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, new ToolRegistry(), VERSION);
    }

    @Test
    void reviewWithoutArgumentsSubmitsPromptWithRequiredSemantics() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("review").handler().execute(build(null, ui));

        assertEquals(1, ui.submitted.size(), "应恰好经对话通道发出一条消息");
        String prompt = ui.submitted.get(0);
        assertTrue(prompt.contains("未提交变更"), "提示词应要求分析未提交变更");
        assertTrue(prompt.contains("只审查不修改"), "提示词应明确只审查不修改");
        assertTrue(prompt.contains("问题"), "提示词应按问题组织");
        assertTrue(prompt.contains("风险"), "提示词应按风险组织");
        assertTrue(prompt.contains("建议"), "提示词应按建议组织");
        assertTrue(prompt.contains("指明具体文件与位置"), "提示词应要求指明具体文件与位置");
        assertTrue(ui.messages.isEmpty(), "命令本身不产生本地输出");
    }

    @Test
    void reviewWithArgumentAppendsFocusToPrompt() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("review").handler().execute(build("特别注意并发安全", ui));

        assertEquals(1, ui.submitted.size(), "应恰好经对话通道发出一条消息");
        String prompt = ui.submitted.get(0);
        assertTrue(prompt.contains("额外关注：特别注意并发安全"), "参数应作为额外关注点并入提示词");
        assertTrue(prompt.contains("未提交变更"), "带参数时提示词仍应要求分析未提交变更");
        assertTrue(ui.messages.isEmpty(), "命令本身不产生本地输出");
    }

    @Test
    void reviewIsPromptTypeWithoutAlias() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        Command review = registry.find("review");
        assertEquals(CommandType.PROMPT, review.type(), "review 属提示词类命令");
        assertTrue(review.aliases().isEmpty(), "review 无别名");
    }
}
