package com.acode;

import com.acode.command.Command;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.command.CommandType;
import com.acode.command.ReviewPrompt;
import com.acode.config.AppConfig;
import com.acode.context.SummaryPrompt;
import com.acode.memory.MemoryType;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.session.SessionCodec;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.acode.ui.MenuEntry;
import com.acode.ui.OutputPane;
import com.acode.ui.SlashCompleter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T13 端到端：真实注册中心 + 真实调度器 + 真实会话控制器（假 provider），按「命令类型与通道」表
 * 逐个执行全部可见命令，核对每种类型的请求条数与去向；并覆盖别名、菜单、规则三态、补全与退出。
 */
class CommandSystemEndToEndTest {

    @TempDir
    Path projectRoot;

    private Path fakeHome;
    private String originalHome;
    private OutputPane output;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        // fakeHome 放 target/ 下：不在 java.io.tmpdir 内（沙箱默认放行 tmpdir），也不在 @TempDir 内（logback 的 acode.log 句柄锁会让 @TempDir 清理失败）
        fakeHome = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "acode-home-");
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
        try (java.util.stream.Stream<Path> paths = Files.walk(fakeHome)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // acode.log 被日志句柄占用时删不掉，残留留在系统临时目录
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(200_000);
        config.setMaxIterations(5);
        config.setMemoryAuto(false);
        return config;
    }

    private ConversationController controller(ChatProvider provider) {
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(projectRoot);
        output = new OutputPane();
        controller.setOutput(output);
        controller.setScreenWriter(new StringWriter());
        controller.initSessionState();
        return controller;
    }

    private static CommandResult line(ConversationController controller, String input) {
        return controller.commandProcessor().handleLine(input);
    }

    private List<String> delta(int before) {
        return output.lines().subList(before, output.lineCount());
    }

    private static long chatRequests(FakeProvider provider) {
        return provider.receivedRequests().stream().filter(r -> !r.tools().isEmpty()).count();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void writeRules(Path file, String rule, String effect) throws IOException {
        write(file, "rules:\n  - rule: " + rule + "\n    effect: " + effect + "\n");
    }

    private static List<String> candidates(CommandRegistry registry, String buffer) {
        List<Candidate> candidates = new ArrayList<>();
        new SlashCompleter(registry).complete(null, parsed(buffer), candidates);
        return candidates.stream().map(Candidate::value).toList();
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

    /** 假选择菜单：按序返回预置下标，耗尽后取消；记录每次调用收到的条目与标题 */
    private static final class FakeMenu {
        private final List<Integer> queue = new ArrayList<>();
        List<MenuEntry> lastEntries = List.of();
        String lastTitle = "";
        int calls;

        FakeMenu(int... selections) {
            for (int s : selections) {
                queue.add(s);
            }
        }

        int select(List<MenuEntry> entries, String title) {
            calls++;
            lastEntries = entries;
            lastTitle = title;
            return queue.isEmpty() ? -1 : queue.remove(0);
        }
    }

    private Tool tool(String name, Permission permission) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Permission permission() {
                return permission;
            }

            @Override
            public JsonNode inputSchema() {
                return mapper.createObjectNode();
            }

            @Override
            public ToolResult execute(JsonNode input, ToolContext context) {
                return ToolResult.success("");
            }
        };
    }

    private ObjectNode args(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    // ---- 组 1：通道表——每个可见命令的请求条数与去向 ----

    @Test
    void everyVisibleCommandBehavesPerChannelTable() {
        FakeProvider provider = FakeProvider.streaming("[]");
        ConversationController controller = controller(provider);
        FakeMenu menu = new FakeMenu(-1);
        controller.setMenuSelector(menu::select);

        int before = output.lineCount();
        int totalBefore = provider.receivedRequests().size();
        long chatBefore = chatRequests(provider);
        line(controller, "/help");
        assertEquals(chatBefore, chatRequests(provider), "/help 不发对话请求");
        assertTrue(delta(before).contains("可用命令："));
        assertTrue(delta(before).contains("输入 /help <命令名> 查看详细用法。"));

        before = output.lineCount();
        line(controller, "/status");
        assertEquals(chatBefore, chatRequests(provider), "/status 不发对话请求");
        assertTrue(delta(before).contains("模式：default"));
        assertTrue(delta(before).contains("版本：v0.1.0"));

        before = output.lineCount();
        line(controller, "/compact");
        assertEquals(chatBefore, chatRequests(provider), "/compact 不发对话请求（摘要走专用请求）");
        assertFalse(delta(before).isEmpty(), "/compact 应输出压缩判断");

        before = output.lineCount();
        line(controller, "/resume");
        assertEquals(chatBefore, chatRequests(provider), "/resume 不发对话请求");
        assertEquals(List.of("（没有可恢复的会话）"), delta(before));

        before = output.lineCount();
        line(controller, "/memory");
        assertEquals(chatBefore, chatRequests(provider), "/memory 不发对话请求");
        assertEquals(1, menu.calls, "无参数应弹菜单");
        assertEquals(List.of("（已取消）"), delta(before));

        before = output.lineCount();
        totalBefore = provider.receivedRequests().size();
        line(controller, "/memory run");
        assertEquals(totalBefore + 1, provider.receivedRequests().size(), "run 发一次提取专用请求");
        assertEquals(chatBefore, chatRequests(provider), "提取请求不走对话通道");
        assertTrue(provider.receivedRequests().get(totalBefore).tools().isEmpty(), "提取请求不携带工具");
        assertEquals(List.of("（没有值得记忆的内容）"), delta(before));

        before = output.lineCount();
        line(controller, "/permission");
        assertEquals(chatBefore, chatRequests(provider), "/permission 不发对话请求");
        assertTrue(delta(before).get(0).contains("当前权限模式：default"));
        assertTrue(String.join("\n", delta(before)).contains("~/.acode/permissions.yaml"));

        before = output.lineCount();
        line(controller, "/clear");
        assertEquals(chatBefore, chatRequests(provider), "/clear 不发对话请求");
        assertEquals(List.of("（已清空）"), output.lines(), "清屏后输出区只剩提示行");

        before = output.lineCount();
        line(controller, "/plan");
        assertEquals(chatBefore, chatRequests(provider), "/plan 无参数不发对话请求");
        assertEquals(List.of("（已进入规划模式：只读探索，计划落盘到 .acode/plans/）"), delta(before));

        before = output.lineCount();
        line(controller, "/do");
        assertEquals(chatBefore, chatRequests(provider), "/do 无计划不发对话请求");
        assertEquals(List.of("（已退出规划模式；没有可执行的计划）"), delta(before));

        before = output.lineCount();
        totalBefore = provider.receivedRequests().size();
        line(controller, "/review 并发安全");
        assertEquals(chatBefore + 1, chatRequests(provider), "/review 发一次对话请求");
        assertEquals(totalBefore + 1, provider.receivedRequests().size(), "review 只发对话请求、无专用请求");
        ChatRequest review = provider.receivedRequests().get(provider.receivedRequests().size() - 1);
        assertEquals(ReviewPrompt.instruction("并发安全"),
                review.messages().get(review.messages().size() - 1).content(), "发出去的应是审查提示词");
        assertTrue(review.messages().get(review.messages().size() - 1).content().contains("额外关注：并发安全"));
        assertTrue(delta(before).stream().noneMatch(l -> l.startsWith("请审查")), "review 不写本地审查行");
    }

    @Test
    void compactAboveThresholdSendsDedicatedSummaryRequestNotChatRequest() {
        FakeProvider provider = FakeProvider.streaming("<summary>压缩摘要</summary>");
        ConversationController controller = controller(provider);
        controller.conversation().addMessage(ChatMessage.of(USER, "x".repeat(40_000)));
        controller.conversation().addMessage(ChatMessage.of(USER, "问题"));

        int totalBefore = provider.receivedRequests().size();
        long chatBefore = chatRequests(provider);
        line(controller, "/compact");

        assertEquals(totalBefore + 1, provider.receivedRequests().size(), "应发一次摘要专用请求");
        assertEquals(chatBefore, chatRequests(provider), "摘要请求不是对话请求");
        ChatRequest summary = provider.receivedRequests().get(provider.receivedRequests().size() - 1);
        assertTrue(summary.tools().isEmpty(), "摘要请求不携带工具");
        assertEquals(SummaryPrompt.instruction(), summary.messages().get(0).content(), "摘要请求的指令与对话无关");
        assertTrue(output.lines().stream().anyMatch(l -> l.contains("压缩完成")), output.lines().toString());
    }

    // ---- 组 2：别名与大小写不敏感查找 ----

    @Test
    void aliasesAndCaseInsensitiveLookupMatchTheirCanonicalCommands() {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        int before = output.lineCount();
        line(controller, "/help");
        List<String> help = delta(before);
        for (String input : List.of("/h", "/?", "/HELP")) {
            before = output.lineCount();
            line(controller, input);
            assertEquals(help, delta(before), input + " 应与 /help 输出逐字相同");
        }

        before = output.lineCount();
        line(controller, "/status");
        List<String> status = delta(before);
        before = output.lineCount();
        line(controller, "/s");
        assertEquals(status, delta(before), "/s 应与 /status 输出逐字相同");

        before = output.lineCount();
        line(controller, "/compact");
        List<String> compact = delta(before);
        before = output.lineCount();
        line(controller, "/c");
        assertEquals(compact.size(), delta(before).size(), "/c 与 /compact 输出结构一致");
        assertEquals(compact.get(0), delta(before).get(0), "/c 与 /compact 首行一致");
    }

    // ---- 组 3：/permission 切档与只读列举、/memory 三层菜单与 run 保留词 ----

    @Test
    void permissionSwitchesModeListsThreeLayersAndNeverWritesRuleFiles() {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        int before = output.lineCount();
        line(controller, "/permission acceptEdits");
        assertEquals(List.of("（已切换到权限模式：acceptEdits）"), delta(before));

        before = output.lineCount();
        line(controller, "/status");
        assertTrue(delta(before).contains("模式：acceptEdits"), "切档后状态随之变化");

        before = output.lineCount();
        line(controller, "/permission nosuchmode");
        assertEquals(List.of("用法：/permission <模式>（default/acceptEdits/plan/bypassPermissions）"), delta(before));

        before = output.lineCount();
        line(controller, "/permission");
        List<String> listed = delta(before);
        assertTrue(listed.get(0).contains("当前权限模式：acceptEdits"), "非法参数不应改模式");
        String joined = String.join("\n", listed);
        assertTrue(joined.contains("用户级") && joined.contains("~/.acode/permissions.yaml"), joined);
        assertTrue(joined.contains("项目级") && joined.contains("<项目根>/.acode/permissions.yaml"), joined);
        assertTrue(joined.contains("项目本地") && joined.contains("<项目根>/.acode/permissions.local.yaml"), joined);

        assertTrue(provider.receivedRequests().isEmpty(), "/permission 全程不发模型请求");
        assertFalse(Files.exists(fakeHome.resolve(".acode").resolve("permissions.yaml")));
        assertFalse(Files.exists(projectRoot.resolve(".acode").resolve("permissions.yaml")));
        assertFalse(Files.exists(projectRoot.resolve(".acode").resolve("permissions.local.yaml")),
                "任何参数都不写规则文件");
    }

    @Test
    void memoryMenuShowsLayersCreatesMissingShowsExistingAndRunIsExtraction() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "第一行\n第二行\n");
        FakeProvider provider = FakeProvider.streaming("[]");
        ConversationController controller = controller(provider);
        FakeMenu menu = new FakeMenu(0, 1);
        controller.setMenuSelector(menu::select);

        int before = output.lineCount();
        line(controller, "/memory");
        assertEquals(1, menu.calls);
        assertEquals(5, menu.lastEntries.size(), "三层 + 分隔行 + 查看长期记忆");
        assertTrue(menu.lastEntries.get(0).label().contains("项目指令")
                && menu.lastEntries.get(0).label().contains("已存在 2 行"), menu.lastEntries.get(0).label());
        assertTrue(menu.lastEntries.get(1).label().contains("本地指令")
                && menu.lastEntries.get(1).label().contains("未创建"), menu.lastEntries.get(1).label());
        assertTrue(menu.lastEntries.get(2).label().contains("用户指令")
                && menu.lastEntries.get(2).label().contains("未创建"), menu.lastEntries.get(2).label());
        assertFalse(menu.lastEntries.get(3).selectable(), "第四项应为不可选分隔行");
        assertTrue(menu.lastEntries.get(4).label().contains("查看长期记忆（0 条）"), menu.lastEntries.get(4).label());
        List<String> shown = delta(before);
        assertEquals(projectRoot.resolve("ACODE.md") + "（2 行）", shown.get(0));
        assertTrue(shown.contains("第一行") && shown.contains("第二行"), "已存在的层应输出完整内容");

        before = output.lineCount();
        line(controller, "/memory");
        assertTrue(delta(before).contains(projectRoot.resolve(".acode").resolve("ACODE.md") + "（已创建空文件）"));
        Path localLayer = projectRoot.resolve(".acode").resolve("ACODE.md");
        assertTrue(Files.isRegularFile(localLayer), "选中不存在的层应创建空文件");
        assertEquals("", Files.readString(localLayer), "创建的文件应为空");

        before = output.lineCount();
        int totalBefore = provider.receivedRequests().size();
        long chatBefore = chatRequests(provider);
        line(controller, "/memory run");
        assertEquals(totalBefore + 1, provider.receivedRequests().size(), "run 应发一次提取专用请求");
        assertEquals(chatBefore, chatRequests(provider), "提取请求不走对话通道");
        assertEquals(List.of("（没有值得记忆的内容）"), delta(before), "run 不是未知参数");
    }

    // ---- 组 4：Agent 可及性——记忆根路径进 system 提示且沙箱放行 ----

    @Test
    void memoryRootsAreVisibleInSystemPromptAndSandbox() throws IOException {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());
        controller.memoryManager().store().write(MemoryType.PROJECT, "build-env", "构建需 JDK21", "正文");
        controller.memoryManager().store().write(MemoryType.USER, "pref", "用户偏好", "正文");
        controller.initSessionState();

        controller.handleExchange("你好", () -> false, () -> { });
        String system = provider.receivedRequests().get(0).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content)
                .findFirst().orElse("");
        Path projectMemoryRoot = projectRoot.resolve(".acode").resolve("memory");
        Path userMemoryRoot = fakeHome.resolve(".acode").resolve("memory");
        assertTrue(system.contains(projectMemoryRoot.toString()), "项目级记忆根路径应进 system：" + system);
        assertTrue(system.contains(userMemoryRoot.toString()), "用户级记忆根路径应进 system：" + system);

        PermissionChecker checker = controller.permissionChecker();
        Tool readFile = tool("ReadFile", Permission.READ);
        PermissionChecker.CheckResult inRoot = checker.check(readFile,
                args("file_path", userMemoryRoot.resolve("pref.md").toString()));
        assertEquals(PermissionMode.Decision.ALLOW, inRoot.decision(),
                "用户级记忆根内的文件不应被沙箱拒绝：" + inRoot.reason() + "（根=" + userMemoryRoot + "）");
        Path outside = fakeHome.resolve("notes.md");
        write(outside, "秘密");
        PermissionChecker.CheckResult outsideRoot = checker.check(readFile,
                args("file_path", outside.toString()));
        assertEquals(PermissionMode.Decision.DENY, outsideRoot.decision(), "项目外非记忆根路径仍应被拒");
    }

    // ---- 组 5：错误路径——未知命令与坏参数都只提示、主循环继续 ----

    @Test
    void unknownCommandAndBadArgsGuideWithoutStoppingTheLoop() {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        int before = output.lineCount();
        assertEquals(CommandResult.CONTINUE, line(controller, "/nosuchcmd"));
        assertEquals(List.of("未知命令：/nosuchcmd（输入 /help 查看可用命令）"), delta(before));

        before = output.lineCount();
        assertEquals(CommandResult.CONTINUE, line(controller, "/memory foobar"));
        assertEquals(List.of("用法：/memory（弹出指令文件菜单）｜ /memory run（立即提取长期记忆）"), delta(before));

        before = output.lineCount();
        assertEquals(CommandResult.CONTINUE, line(controller, "/help"));
        assertTrue(delta(before).contains("可用命令："), "错误之后主循环照常可用");
    }

    // ---- 组 6：quit 可见、静默退出；隐藏命令不进帮助与补全但可调用 ----

    @Test
    void quitIsVisibleLastInLocalSectionExitsSilentlyAndHiddenStaysDispatchable() {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        int before = output.lineCount();
        line(controller, "/help");
        List<String> help = delta(before);
        int quitIdx = -1;
        for (int i = 0; i < help.size(); i++) {
            if (help.get(i).startsWith("  /quit")) {
                quitIdx = i;
            }
        }
        assertTrue(quitIdx >= 0, "quit 应出现在帮助列表");
        assertTrue(quitIdx + 1 < help.size() && help.get(quitIdx + 1).isEmpty(), "quit 应为本地段末位");
        assertTrue(candidates(controller.commandRegistry, "/").contains("/quit"), "quit 应进补全候选");

        before = output.lineCount();
        assertEquals(CommandResult.EXIT, line(controller, "/quit"), "quit 返回退出");
        assertEquals(before, output.lineCount(), "quit 不产生任何输出");

        controller.commandRegistry.register(new Command("hiddenx", List.of(), "隐藏", "/hiddenx",
                CommandType.LOCAL, null, true,
                ctx -> {
                    ctx.ui().appendSystemMessage("hidden-ran");
                    return CommandResult.CONTINUE;
                }));
        before = output.lineCount();
        line(controller, "/help");
        assertTrue(delta(before).stream().noneMatch(l -> l.contains("hiddenx")), "隐藏命令不进帮助");
        assertFalse(candidates(controller.commandRegistry, "/").contains("/hiddenx"), "隐藏命令不进补全");
        before = output.lineCount();
        assertEquals(CommandResult.CONTINUE, line(controller, "/hiddenx"));
        assertEquals(List.of("hidden-ran"), delta(before), "隐藏命令仍可调用");
    }

    // ---- 组 7：状态一致性——切档进 /status、写记忆进条数、ask 规则走确认链 ----

    @Test
    void permissionModeMemoryCountAndAskRuleFlowThroughTheRealChain() throws IOException {
        writeRules(fakeHome.resolve(".acode").resolve("permissions.yaml"), "Bash(*)", "allow");
        writeRules(projectRoot.resolve(".acode").resolve("permissions.local.yaml"), "Bash(git push *)", "ask");

        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        int before = output.lineCount();
        line(controller, "/permission acceptEdits");
        before = output.lineCount();
        line(controller, "/status");
        assertTrue(delta(before).contains("模式：acceptEdits"));
        assertTrue(delta(before).stream().anyMatch(l -> l.contains("记忆：user 0 条")), delta(before).toString());

        controller.memoryManager().store().write(MemoryType.USER, "pref-7", "用户偏好", "正文");
        before = output.lineCount();
        line(controller, "/status");
        assertTrue(delta(before).stream().anyMatch(l -> l.contains("记忆：user 1 条")),
                "直接写记忆后 /status 条数随之增加");

        PermissionChecker checker = controller.permissionChecker();
        Tool bash = tool("Bash", Permission.EXEC);
        ObjectNode call = args("command", "git push origin main");
        PermissionChecker.CheckResult asked = checker.check(bash, call);
        assertEquals(PermissionMode.Decision.ASK, asked.decision(),
                "命中 ask 规则应进确认通道（不被他层 allow 盖掉）");
        checker.addAllowAlwaysRule("Bash", "git push origin main");
        assertEquals(PermissionMode.Decision.ALLOW, checker.check(bash, call).decision(),
                "始终允许后同一工具调用应放行（第 ⑦ 层未跳过）");
    }

    // ---- 组 8：/resume 只弹菜单、零模型请求 ----

    @Test
    void resumeOnlyPopsSessionMenuWithoutModelRequests() throws IOException {
        Path sessions = projectRoot.resolve(".acode").resolve("sessions");
        write(sessions.resolve("20260101-120000-0001.jsonl"),
                SessionCodec.encode(ChatMessage.of(USER, "上次的问题"), System.currentTimeMillis() / 1000) + "\n");

        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);
        FakeMenu menu = new FakeMenu(-1);
        controller.setMenuSelector(menu::select);

        int before = output.lineCount();
        line(controller, "/resume");
        assertEquals(1, menu.calls, "应弹会话选择菜单");
        assertEquals("（↑/↓ 选择会话，回车加载，Esc 取消）", menu.lastTitle);
        assertEquals(1, menu.lastEntries.size(), "菜单应列出可恢复会话");
        assertEquals(List.of("（已取消）"), delta(before));
        assertTrue(provider.receivedRequests().isEmpty(), "/resume 全程不发模型请求");
    }

    // ---- 组 9：/plan 交付 → /do 按计划执行（计划状态只消费一次） ----

    @Test
    void planDeliveryThenDoSubmitsThePlanBody() throws IOException {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("# 登录接口计划\n第一步建表"),
                        FakeProvider.toolUse("id-1", "ExitPlanMode", mapper.createObjectNode()),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("执行中"), FakeProvider.complete())));
        ConversationController controller = controller(provider);

        long chatBefore = chatRequests(provider);
        line(controller, "/plan 设计用户认证");
        assertEquals(chatBefore + 1, chatRequests(provider), "带参数进入规划模式并发起规划对话");
        assertTrue(output.lines().contains("（计划已交付）"), output.lines().toString());
        Path plan = controller.lastDeliveredPlanPath();
        assertNotNull(plan, "规划交付后应记录计划落盘位置");
        assertTrue(Files.exists(plan));

        chatBefore = chatRequests(provider);
        line(controller, "/do");
        assertEquals(chatBefore + 1, chatRequests(provider), "有计划时发对话请求");
        assertTrue(output.lines().contains("（已退出规划模式，按计划开始执行）"));
        List<ChatMessage> messages =
                provider.receivedRequests().get(provider.receivedRequests().size() - 1).messages();
        assertEquals(Files.readString(plan, StandardCharsets.UTF_8), messages.get(messages.size() - 1).content(),
                "执行模式应把计划正文发给 Agent");

        assertNull(controller.lastDeliveredPlanPath(), "计划发出后应消费该状态");
        line(controller, "/do");
        assertEquals(chatBefore + 1, chatRequests(provider), "第二次 /do 回到无计划分支、不发对话请求");
        assertTrue(output.lines().contains("（已退出规划模式；没有可执行的计划）"), output.lines().toString());
    }

    // ---- 组 10：补全候选来自可见清单、按注册顺序、前缀过滤 ----

    @Test
    void completionComesFromVisibleRegistryInRegistrationOrder() {
        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);

        assertEquals(List.of("/help", "/compact", "/resume", "/memory", "/permission", "/status", "/quit",
                        "/clear", "/plan", "/do", "/review"),
                candidates(controller.commandRegistry, "/"), "候选顺序与注册顺序一致");
        assertEquals(List.of("/compact"), candidates(controller.commandRegistry, "/com"), "按前缀过滤");
        assertEquals(List.of("/help"), candidates(controller.commandRegistry, "/HELP"), "前缀大小写不敏感");
        assertTrue(candidates(controller.commandRegistry, "/nosuch").isEmpty(), "0 候选不报错");

        controller.commandRegistry.register(new Command("ghost", List.of(), "隐藏", "/ghost",
                CommandType.LOCAL, null, true, ctx -> CommandResult.CONTINUE));
        assertFalse(candidates(controller.commandRegistry, "/").contains("/ghost"), "隐藏项缺席补全候选");
    }
}
