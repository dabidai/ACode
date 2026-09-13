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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /clear、/plan、/do 三条本地界面命令：形态与副作用（切模式、发用户输入、清屏开新会话） */
class PlanDoCommandTest {

    private static final String VERSION = "v0.1.0";

    @TempDir
    Path tempDir;

    /** 界面操作接口测试桩：记录系统消息、提交的用户输入、规划模式开关、清屏调用与计划落盘位置 */
    private static final class FakeUi implements UIController {
        private final List<String> messages = new ArrayList<>();
        private final List<String> submitted = new ArrayList<>();
        private final List<Boolean> planModes = new ArrayList<>();
        private Path planPath;
        private boolean cleared;

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
            planModes.add(enabled);
        }

        @Override
        public ContextUsage contextUsage() {
            return new ContextUsage(0, 0);
        }

        @Override
        public int selectMenu(List<MenuEntry> entries, String title) {
            return -1;
        }

        @Override
        public void clearScreenAndNewSession() {
            cleared = true;
            // 模拟 T12 清除钩子：/clear 后待执行计划状态清空
            planPath = null;
        }

        @Override
        public Path lastDeliveredPlanPath() {
            return planPath;
        }

        void deliverPlan(Path path) {
            this.planPath = path;
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
    void planWithoutArgumentsOnlySwitchesModeAndOutputsHint() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("plan").handler().execute(build(null, ui));

        assertEquals(List.of(true), ui.planModes, "应切入规划模式");
        assertTrue(ui.submitted.isEmpty(), "无参数不发起模型请求");
        assertEquals(List.of("（已进入规划模式：只读探索，计划落盘到 .acode/plans/）"), ui.messages);
    }

    @Test
    void planWithTaskArgumentSwitchesModeAndSubmitsTaskText() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("plan").handler().execute(build("设计用户认证", ui));

        assertEquals(List.of(true), ui.planModes, "应切入规划模式");
        assertEquals(List.of("设计用户认证"), ui.submitted, "任务文本应原样发给 Agent");
        assertTrue(ui.messages.isEmpty(), "带参数时只发任务、不输出系统消息");
    }

    @Test
    void doWithoutPendingPlanOnlySwitchesBackAndReportsNoPlan() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("do").handler().execute(build(null, ui));

        assertEquals(List.of(false), ui.planModes, "应退出规划模式");
        assertTrue(ui.submitted.isEmpty(), "无计划不发起模型请求");
        assertEquals(List.of("（已退出规划模式；没有可执行的计划）"), ui.messages);
    }

    @Test
    void doAfterPlanDeliverySubmitsPlanContent() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();
        String plan = "## 用户认证方案\n1. 建表\n2. 实现接口\n";
        Path planFile = tempDir.resolve(".acode").resolve("plans").resolve("plan-auth.md");
        Files.createDirectories(planFile.getParent());
        Files.writeString(planFile, plan, StandardCharsets.UTF_8);
        ui.deliverPlan(planFile);

        registry.find("do").handler().execute(build(null, ui));

        assertEquals(List.of(false), ui.planModes, "应退出规划模式");
        assertEquals(List.of(plan), ui.submitted, "发出去的任务文本应为计划正文");
        assertEquals(List.of("（已退出规划模式，按计划开始执行）"), ui.messages);
    }

    @Test
    void clearThenDoFallsBackToNoPlanBranch() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();
        ui.deliverPlan(tempDir.resolve("plan-x.md"));

        registry.find("clear").handler().execute(build(null, ui));
        assertEquals(List.of("（已清空）"), ui.messages, "clear 应输出既有提示");
        assertTrue(ui.cleared, "clear 应清屏并开启新会话");

        registry.find("do").handler().execute(build(null, ui));

        assertTrue(ui.submitted.isEmpty(), "clear 后计划状态清空，/do 不发模型请求");
        assertEquals(List.of("（已清空）", "（已退出规划模式；没有可执行的计划）"), ui.messages);
    }

    @Test
    void clearClearsScreenAndStartsNewSession() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();

        registry.find("clear").handler().execute(build(null, ui));

        assertTrue(ui.cleared, "clear 应触发清屏并开启新会话");
        assertTrue(ui.submitted.isEmpty(), "clear 不发起模型请求");
        assertTrue(ui.planModes.isEmpty(), "clear 不切规划模式");
    }
}
