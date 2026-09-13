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
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.ui.MenuEntry;
import com.acode.ui.SlashCompleter;
import com.acode.ui.UIController;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpStatusCommandTest {

    private static final String VERSION = "v0.1.0";

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

    private CommandContext build(String args, FakeUi ui, MemoryStore store, ToolRegistry tools) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        FakeProvider provider = FakeProvider.streaming("hi");
        return new CommandContext(args, ui,
                new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                        new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                                tempDir.resolve("permissions.project.yaml"),
                                tempDir.resolve("permissions.local.yaml"))),
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(store, conversation, provider, false),
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, tools, VERSION);
    }

    private MemoryStore store() {
        return new MemoryStore(MemoryScope.project(() -> tempDir.resolve("project")),
                MemoryScope.user(() -> tempDir.resolve("user")));
    }

    private ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep")) {
            registry.register(new BaseTool(name, name + " 的描述", Permission.READ) {
                @Override
                protected List<ParamSpec> paramSpecs() {
                    return List.of();
                }

                @Override
                protected ToolResult doExecute(JsonNode input, ToolContext context) {
                    return ToolResult.success(name);
                }
            });
        }
        return registry;
    }

    private static Command fake(String name, CommandType type) {
        return new Command(name, List.of(), "desc of " + name, "/" + name,
                type, null, false, ctx -> CommandResult.CONTINUE);
    }

    private static String nameColumn(Command command) {
        StringBuilder sb = new StringBuilder("/").append(command.name());
        for (String alias : command.aliases()) {
            sb.append(", /").append(alias);
        }
        return sb.toString();
    }

    private List<String> run(CommandRegistry registry, String name, String args, FakeUi ui) {
        return run(registry, name, args, ui, store(), tools());
    }

    private List<String> run(CommandRegistry registry, String name, String args, FakeUi ui,
                             MemoryStore store, ToolRegistry tools) {
        registry.find(name).handler().execute(build(args, ui, store, tools));
        return ui.messages();
    }

    private static ParsedLine parsed(String text) {
        return new ParsedLine() {
            @Override
            public String word() {
                return text;
            }

            @Override
            public int wordCursor() {
                return text.length();
            }

            @Override
            public int wordIndex() {
                return 0;
            }

            @Override
            public List<String> words() {
                return List.of(text);
            }

            @Override
            public String line() {
                return text;
            }

            @Override
            public int cursor() {
                return text.length();
            }
        };
    }

    @Test
    void helpListsEveryVisibleCommandIncludingQuit() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        List<String> lines = run(registry, "help", null, ui);
        String output = String.join("\n", lines);

        assertEquals("可用命令：", lines.get(0));
        assertEquals("输入 /help <命令名> 查看详细用法。", lines.get(lines.size() - 1));
        for (Command command : registry.visible()) {
            assertTrue(output.contains(nameColumn(command)), "帮助应含 /" + command.name());
        }
        assertTrue(output.contains("/quit"), "退出命令应出现在帮助中");
        long nameLines = lines.stream().filter(l -> l.startsWith("  /")).count();
        assertEquals(registry.visible().size(), nameLines, "帮助条数应与可见清单一致");
    }

    @Test
    void helpGroupsSectionsByTypeInRegistrationOrder() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        registry.register(fake("review", CommandType.PROMPT));
        registry.register(fake("execute", CommandType.LOCAL_UI));

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        List<String> lines = run(registry, "help", null, ui);

        List<String> expected = new ArrayList<>();
        for (CommandType type : CommandType.values()) {
            for (Command command : registry.visible()) {
                if (command.type() == type) {
                    expected.add(nameColumn(command));
                }
            }
        }
        List<String> actual = new ArrayList<>();
        List<Integer> actualIdx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("  /")) {
                actual.add(lines.get(i).substring(2).split(" {2,}")[0]);
                actualIdx.add(i);
            }
        }
        assertEquals(expected, actual, "三段顺序与段内注册顺序应一致");

        List<Integer> groupSizes = new ArrayList<>();
        for (CommandType type : CommandType.values()) {
            long size = registry.visible().stream().filter(c -> c.type() == type).count();
            if (size > 0) {
                groupSizes.add((int) size);
            }
        }
        int pos = 0;
        for (int g = 0; g < groupSizes.size(); g++) {
            int end = pos + groupSizes.get(g);
            for (int i = pos; i < end - 1; i++) {
                assertEquals(actualIdx.get(i) + 1, actualIdx.get(i + 1), "段内不应有空行");
            }
            if (g < groupSizes.size() - 1) {
                assertEquals(actualIdx.get(end - 1) + 2, actualIdx.get(end), "段间应恰好一个空行");
                assertTrue(lines.get(actualIdx.get(end) - 1).isEmpty(), "段间应为空行");
            }
            pos = end;
        }
    }

    @Test
    void helpDetailShowsDescriptionUsageArgumentsAndAliases() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String output = String.join("\n", run(registry, "help", "compact", ui));

        assertTrue(output.contains("描述：压缩上下文"));
        assertTrue(output.contains("用法：/compact"));
        assertTrue(output.contains("参数：<需要保留的重点>"));
        assertTrue(output.contains("别名：/c"));
    }

    @Test
    void helpDetailOmitsAliasAndArgumentLinesWhenAbsent() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String output = String.join("\n", run(registry, "help", "quit", ui));

        assertTrue(output.contains("描述：退出程序"));
        assertTrue(output.contains("用法：/quit"));
        assertFalse(output.contains("别名"), "无别名命令不应显示别名行");
        assertFalse(output.contains("参数"), "无参数说明的命令不应显示参数行");
    }

    @Test
    void helpUnknownCommandPointsBackToHelp() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String output = String.join("\n", run(registry, "help", "nope", ui));

        assertTrue(output.contains("未找到命令：/nope"));
        assertTrue(output.contains("/help"), "未找到时应指向帮助");
    }

    @Test
    void statusShowsModeDirectoryAndVersion() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        List<String> lines = run(registry, "status", null, ui);
        String output = String.join("\n", lines);

        assertEquals("ACode 状态", lines.get(0));
        assertEquals("─".repeat(13), lines.get(1));
        assertTrue(output.contains("模式：default"));
        assertTrue(output.contains("工作目录：" + tempDir));
        assertTrue(output.contains("版本：" + VERSION));
    }

    @Test
    void statusTokenLineGroupsThousandsAndRoundsPercent() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(45_230, 200_000));
        String output = String.join("\n", run(registry, "status", null, ui));

        assertTrue(output.contains("Token：45,230 / 200,000（23%）"),
                "千位应加逗号、占比按占用除以上限取整");
    }

    @Test
    void statusToolCountMatchesAvailableTools() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        ToolRegistry tools = tools();
        tools.disable("Bash");

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String output = String.join("\n", run(registry, "status", null, ui, store(), tools));

        assertEquals(5, tools.availableList().size());
        assertTrue(output.contains("工具：" + tools.availableList().size() + " 个已启用"),
                "工具数应与注册表已启用数量一致");
    }

    @Test
    void statusMemoryCountsMatchStoreByType() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);
        MemoryStore store = store();
        writeSome(store, MemoryType.USER, 3, "pref");
        writeSome(store, MemoryType.FEEDBACK, 1, "fb");
        writeSome(store, MemoryType.PROJECT, 5, "proj");
        writeSome(store, MemoryType.REFERENCE, 2, "ref");

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String output = String.join("\n", run(registry, "status", null, ui, store, tools()));

        String expected = "记忆：user " + countOf(store, MemoryType.USER) + " 条"
                + " · feedback " + countOf(store, MemoryType.FEEDBACK) + " 条"
                + " · project " + countOf(store, MemoryType.PROJECT) + " 条"
                + " · reference " + countOf(store, MemoryType.REFERENCE) + " 条";
        assertEquals("记忆：user 3 条 · feedback 1 条 · project 5 条 · reference 2 条", expected);
        assertTrue(output.contains(expected), "四类记忆条数应与存储实际一致");
    }

    private static void writeSome(MemoryStore store, MemoryType type, int n, String prefix) {
        for (int i = 1; i <= n; i++) {
            assertTrue(store.write(type, prefix + "-" + i, "desc " + i, "body"));
        }
    }

    private static long countOf(MemoryStore store, MemoryType type) {
        return store.listAll().stream().filter(m -> m.type() == type).count();
    }

    @Test
    void quitReturnsExitAndProducesNoOutput() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        Command quit = registry.find("quit");
        CommandResult result = quit.handler().execute(build(null, ui, store(), tools()));

        assertEquals(CommandResult.EXIT, result);
        assertTrue(quit.aliases().isEmpty(), "退出命令无别名");
        assertTrue(ui.messages().isEmpty(), "退出命令不应产生任何输出");
    }

    @Test
    void quitIsVisibleInHelpAndCompletion() {
        CommandRegistry registry = new CommandRegistry();
        BuiltinCommands.registerAll(registry);

        FakeUi ui = new FakeUi(new UIController.ContextUsage(0, 0));
        String help = String.join("\n", run(registry, "help", null, ui));
        assertTrue(help.contains("/quit"), "退出命令应出现在帮助输出中");

        List<Candidate> candidates = new ArrayList<>();
        new SlashCompleter(registry).complete(null, parsed("/"), candidates);
        assertTrue(candidates.stream().map(Candidate::value).toList().contains("/quit"),
                "退出命令应出现在补全候选中");
    }
}
