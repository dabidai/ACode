package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.context.SummaryPrompt;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.provider.ProviderException;
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

/** /compact 命令：5000 token 阈值短路、三段式输出、保留重点进摘要指令、失败兜底。 */
class CompactCommandTest {

    @TempDir
    Path tempDir;

    /** 界面操作接口测试桩：收集系统消息、可注入上下文占用快照 */
    private static final class FakeUi implements UIController {
        private final List<String> messages = new ArrayList<>();
        private final ContextUsage usage;

        FakeUi(ContextUsage usage) {
            this.usage = usage;
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
            return usage;
        }

        @Override
        public int selectMenu(List<MenuEntry> entries, String title) {
            return -1;
        }

        List<String> messages() {
            return messages;
        }
    }

    /** 可压缩历史：超大旧内容（10000 token）+ 最新未答复 user */
    private static Conversation compressibleConversation() {
        Conversation c = new Conversation("test-model", false, 8192, 200_000);
        c.addMessage(ChatMessage.of(ChatMessage.Role.USER, "x".repeat(40_000)));
        c.addMessage(ChatMessage.of(ChatMessage.Role.USER, "问题"));
        return c;
    }

    private CommandContext build(String args, FakeUi ui, FakeProvider provider,
                                 Conversation conversation) {
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
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, new ToolRegistry(), "v0.1.0");
    }

    private List<String> runCompact(String args, FakeUi ui, FakeProvider provider,
                                    Conversation conversation) {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        registry.find("compact").handler().execute(build(args, ui, provider, conversation));
        return ui.messages();
    }

    @Test
    void belowThresholdSkipsProviderWithoutCompressing() {
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        Conversation conversation = compressibleConversation();
        FakeUi ui = new FakeUi(new UIController.ContextUsage(4_999, 200_000));
        String output = String.join("\n", runCompact(null, ui, provider, conversation));

        assertTrue(output.contains("（当前上下文约 4999 token，无需压缩）"), output);
        assertTrue(provider.receivedRequests().isEmpty(), "低于阈值不应发任何模型请求");
        assertEquals(2, conversation.messageCount(), "历史应原样不动");
    }

    @Test
    void compactAboveThresholdRebuildsHistoryWithThreePhaseOutput() {
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        Conversation conversation = compressibleConversation();
        FakeUi ui = new FakeUi(new UIController.ContextUsage(40_000, 200_000));
        String output = String.join("\n", runCompact(null, ui, provider, conversation));

        assertTrue(output.contains("正在压缩…"), "第一阶段提示：" + output);
        assertTrue(output.contains("压缩完成：压缩前约 ") && output.contains(" token → 压缩后约 "),
                "第三阶段结果行：" + output);
        assertEquals(3, conversation.messageCount(), "重建为 摘要+边界+未闭环步");
        assertTrue(conversation.history().get(0).content().contains("压缩正文"), "摘要正文入历史");
    }

    @Test
    void compactPassesFocusIntoSummaryInstruction() {
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        Conversation conversation = compressibleConversation();
        FakeUi ui = new FakeUi(new UIController.ContextUsage(40_000, 200_000));
        runCompact("数据库迁移方案", ui, provider, conversation);

        String system = provider.receivedRequests().get(0).messages().get(0).content();
        assertTrue(system.contains("压缩时请特别保留：数据库迁移方案"), "摘要指令末尾应含保留重点");
    }

    @Test
    void compactWithoutFocusKeepsSummaryInstructionUnchanged() {
        FakeProvider provider = FakeProvider.streaming("<summary>压缩正文</summary>");
        Conversation conversation = compressibleConversation();
        FakeUi ui = new FakeUi(new UIController.ContextUsage(40_000, 200_000));
        runCompact(null, ui, provider, conversation);

        String system = provider.receivedRequests().get(0).messages().get(0).content();
        assertEquals(SummaryPrompt.instruction(), system, "不带重点时摘要指令逐字不变");
    }

    @Test
    void compactShortHistoryReportsNothingToCompact() {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, "你好"));
        FakeProvider provider = FakeProvider.streaming("摘要");
        FakeUi ui = new FakeUi(new UIController.ContextUsage(40_000, 200_000));
        String output = String.join("\n", runCompact(null, ui, provider, conversation));

        assertTrue(output.contains("（没有需要压缩的内容）"), output);
        assertTrue(provider.receivedRequests().isEmpty(), "无变化时不发摘要请求");
    }

    @Test
    void compactFailureFallsBackToErrorMessage() {
        FakeProvider provider = FakeProvider.failing(new ProviderException("摘要生成失败"));
        Conversation conversation = compressibleConversation();
        FakeUi ui = new FakeUi(new UIController.ContextUsage(40_000, 200_000));
        String output = String.join("\n", runCompact(null, ui, provider, conversation));

        assertTrue(output.contains("压缩失败：摘要生成失败"), output);
    }

    @Test
    void compactAliasResolvesToSameCommand() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        assertEquals(registry.find("compact"), registry.find("c"), "别名应指向同一命令");
    }
}
