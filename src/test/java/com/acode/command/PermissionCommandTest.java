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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /permission 命令：无参列举三层规则、四档模式切换、规则文件只读与参数约束。 */
class PermissionCommandTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path fakeHome;
    private Path userFile;
    private Path projectFile;
    private Path localFile;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        fakeHome = tempDir.resolve("home");
        System.setProperty("user.home", fakeHome.toString());
        userFile = fakeHome.resolve(".acode").resolve("permissions.yaml");
        projectFile = tempDir.resolve(".acode").resolve("permissions.yaml");
        localFile = tempDir.resolve(".acode").resolve("permissions.local.yaml");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    /** 界面操作接口测试桩：收集系统消息 */
    private static final class FakeUi implements UIController {
        private final List<String> messages = new ArrayList<>();

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
            return -1;
        }

        List<String> messages() {
            return messages;
        }
    }

    private PermissionChecker checker() {
        return new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                new RuleEngine(userFile, projectFile, localFile));
    }

    private CommandContext build(String args, FakeUi ui, PermissionChecker checker) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        FakeProvider provider = FakeProvider.streaming("回答");
        return new CommandContext(args, ui, checker,
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(new MemoryStore(
                        MemoryScope.project(() -> tempDir.resolve("project")),
                        MemoryScope.user(() -> fakeHome.resolve("user"))),
                        conversation, provider, false),
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, new ToolRegistry(), "v0.1.0");
    }

    private List<String> runPermission(String args, FakeUi ui, PermissionChecker checker) {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        registry.find("permission").handler().execute(build(args, ui, checker));
        return ui.messages();
    }

    private void writeRules(Path file, String rulesBlock) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "rules:\n" + rulesBlock, StandardCharsets.UTF_8);
    }

    /** 断言某层整行：标签开头、含简写路径、以条数结尾（中间列按最长路径对齐，空白数不定） */
    private static void assertLayerLine(String output, String label, String shortPath, String count) {
        assertTrue(output.lines().anyMatch(l -> l.startsWith(label + "  ")
                        && l.contains(shortPath) && l.endsWith(" " + count)),
                label + " 行应含 " + shortPath + " 与 " + count + "：" + output);
    }

    @Test
    void permissionNoArgListsModeThreeLayersAndRules() throws IOException {
        writeRules(userFile, "  - rule: \"ReadFile(*)\"\n    effect: allow\n");
        writeRules(projectFile, "  - rule: \"Bash(npm *)\"\n    effect: ask\n");
        writeRules(localFile, "  - rule: \"WriteFile(*)\"\n    effect: deny\n");
        FakeUi ui = new FakeUi();

        String output = String.join("\n", runPermission(null, ui, checker()));

        assertTrue(output.contains("当前权限模式：default"), output);
        assertLayerLine(output, "用户级", "~/.acode/permissions.yaml", "1 条");
        assertTrue(output.contains("  ReadFile(*) → allow"), output);
        assertLayerLine(output, "项目级", "<项目根>/.acode/permissions.yaml", "1 条");
        assertTrue(output.contains("  Bash(npm *) → ask"), output);
        assertLayerLine(output, "项目本地", "<项目根>/.acode/permissions.local.yaml", "1 条");
        assertTrue(output.contains("  WriteFile(*) → deny"), output);
    }

    @Test
    void permissionNoArgShowsZeroCountForEmptyLayers() {
        FakeUi ui = new FakeUi();

        String output = String.join("\n", runPermission(null, ui, checker()));

        assertLayerLine(output, "用户级", "~/.acode/permissions.yaml", "0 条");
        assertLayerLine(output, "项目级", "<项目根>/.acode/permissions.yaml", "0 条");
        assertLayerLine(output, "项目本地", "<项目根>/.acode/permissions.local.yaml", "0 条");
    }

    @Test
    void permissionSwitchesAllFourModes() {
        List<PermissionMode> modes = List.of(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN, PermissionMode.BYPASS);
        for (PermissionMode mode : modes) {
            PermissionChecker checker = checker();
            FakeUi ui = new FakeUi();
            String output = String.join("\n", runPermission(mode.configValue(), ui, checker));

            assertTrue(output.contains("（已切换到权限模式：" + mode.configValue() + "）"), output);
            assertEquals(mode, checker.mode(), "切档后 checker 模式应生效");
        }
    }

    @Test
    void permissionInvalidArgShowsUsageAndKeepsMode() {
        PermissionChecker checker = checker();
        FakeUi ui = new FakeUi();

        String output = String.join("\n", runPermission("yolo", ui, checker));

        assertTrue(output.contains("用法：/permission <模式>（default/acceptEdits/plan/bypassPermissions）"), output);
        assertEquals(PermissionMode.DEFAULT, checker.mode(), "非法值不应切换模式");
    }

    @Test
    void permissionModeMatchIsCaseSensitive() {
        PermissionChecker checker = checker();
        FakeUi ui = new FakeUi();

        runPermission("ACCEPT_EDITS", ui, checker);

        assertEquals(PermissionMode.DEFAULT, checker.mode(), "大小写敏感：大写形式非法、模式不变");
    }

    @Test
    void permissionExtraWordRejected() {
        PermissionChecker checker = checker();
        FakeUi ui = new FakeUi();

        runPermission("acceptEdits extra", ui, checker);

        assertEquals(PermissionMode.DEFAULT, checker.mode(), "多余参数非法、模式不变");
    }

    @Test
    void permissionArgsNeverWriteRuleFiles() throws IOException {
        writeRules(userFile, "  - rule: \"ReadFile(*)\"\n    effect: allow\n");
        writeRules(projectFile, "  - rule: \"Bash(npm *)\"\n    effect: ask\n");
        writeRules(localFile, "  - rule: \"WriteFile(*)\"\n    effect: deny\n");
        byte[] userBefore = Files.readAllBytes(userFile);
        byte[] projectBefore = Files.readAllBytes(projectFile);
        byte[] localBefore = Files.readAllBytes(localFile);

        for (String args : java.util.Arrays.asList(null, "default", "acceptEdits", "plan",
                "bypassPermissions", "yolo")) {
            PermissionChecker checker = checker();
            FakeUi ui = new FakeUi();
            runPermission(args, ui, checker);
            assertFalse(ui.messages().isEmpty(), "每次执行都应有输出：" + args);
        }

        assertTrue(java.util.Arrays.equals(userBefore, Files.readAllBytes(userFile)),
                "用户级规则文件字节不应变化");
        assertTrue(java.util.Arrays.equals(projectBefore, Files.readAllBytes(projectFile)),
                "项目级规则文件字节不应变化");
        assertTrue(java.util.Arrays.equals(localBefore, Files.readAllBytes(localFile)),
                "本地规则文件字节不应变化");
    }

    @Test
    void permissionHelpDetailMentionsFourModesAndReadOnlyRules() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        FakeUi ui = new FakeUi();
        registry.find("help").handler().execute(build("permission", ui, checker()));

        String output = String.join("\n", ui.messages());
        assertTrue(output.contains("只接受四档模式之一（default/acceptEdits/plan/bypassPermissions）"), output);
        assertTrue(output.contains("规则只读"), output);
    }
}
