package com.acode;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.agent.AgentEvent.UsageEvent;
import com.acode.agent.AskUserTool;
import com.acode.agent.EventConfirmationGate;
import com.acode.agent.ExitPlanModeTool;
import com.acode.command.BuiltinCommands;
import com.acode.command.CommandContext;
import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.config.AppConfig;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.config.ConfigValidator;
import com.acode.context.ContextManager;
import com.acode.conversation.Conversation;
import com.acode.mcp.McpManager;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionResponse;
import com.acode.permission.RuleEngine;
import com.acode.prompt.EnvironmentDetector;
import com.acode.prompt.ProjectInstructions;
import com.acode.prompt.PromptBuilder;
import com.acode.prompt.SystemReminder;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ContentBlock;
import com.acode.provider.ProviderException;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.Usage;
import com.acode.provider.anthropic.AnthropicProvider;
import com.acode.provider.openai.OpenAiProvider;
import com.acode.session.Session;
import com.acode.session.SessionLoader;
import com.acode.session.SessionManager;
import com.acode.session.SessionRecorder;
import com.acode.session.SessionStore;
import com.acode.tool.DefaultToolset;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.ConfirmationPrompt;
import com.acode.ui.HistoryRenderer;
import com.acode.ui.InputPane;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.MenuEntry;
import com.acode.ui.OutputPane;
import com.acode.ui.PromptAnswerer;
import com.acode.ui.RenderContext;
import com.acode.ui.SelectionMenu;
import com.acode.ui.StatusBar;
import com.acode.ui.StreamPrinter;
import com.acode.ui.TerminalMenuKeySource;
import com.acode.ui.TerminalUIController;
import com.acode.ui.ToolCallDisplay;
import com.acode.ui.UIController;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 主循环与装配：配置 → Provider → 会话 → TUI 串成完整对话。
 * Provider 在后台 daemon 线程流式生成，主线程负责重绘与 Ctrl+C 中断检测；
 * 退出时把完整消息历史存为独立会话文件。
 */
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private static final int MAX_TOKENS = 8192;

    /** 版本串：横幅与 /status 同源（不新增版本机制，只是把艺术字里的串提炼出来） */
    static final String VERSION = "v0.1.0";

    /** 启动横幅（版本行引用 {@link #VERSION}，与命令层读同一处）；包可见供测试断言同源 */
    static final String BANNER = """
             ___   ____    ___   ___   ____
            / _ \\ / ___|  / _ \\ / _ \\ |  _ \\
           | | | | |     | | | | | | || | | |
           | |_| | |___  | |_| | |_| || |_| |
            \\___/ \\____|  \\___/ \\___/ |____/
                          ACode %s
            """.formatted(VERSION);

    private final ChatProvider provider;
    private final AppConfig config;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    /** 命令注册中心：构造期装配全部内置命令（注册顺序即帮助与补全展示顺序）；包可见供测试断言 */
    final CommandRegistry commandRegistry;
    private final boolean resume;
    private final SessionManager sessionManager;
    private boolean sessionManagerAttached;

    /** plan 模式开关：/plan 进入、/do 退出；作用于下一次 exchange 新建的 Agent */
    private boolean planMode = false;

    /** 最近一次规划交付的计划落盘位置（会话内状态，重启即无记录，走无计划分支）；复位动作归 T12 清除钩子 */
    private Path deliveredPlanPath;

    private AcodeTerminal tui;
    private OutputPane output;
    private final RenderContext renderContext;

    private PromptAnswerer promptAnswerer;

    /** 确认应答器：收到 ConfirmationRequestEvent 后渲染提示并返回三选一；测试可注入替身。 */
    private Function<ConfirmationRequestEvent, PermissionResponse> confirmAnswerer =
            event -> promptAnswerer().answerConfirmationPrompt(event);

    /** 选择应答器：收到 ChoiceRequestEvent 后弹多选项菜单并返回选中项（取消返回 null）；测试可注入替身。 */
    private Function<ChoiceRequestEvent, String> choiceAnswerer =
            event -> promptAnswerer().answerChoicePrompt(event);

    /** 权限检查器：在 handleExchange 装配注入；/permission 命令即时切档用。 */
    private PermissionChecker permissionChecker;

    private ExchangeRunner exchangeRunner;
    private CommandProcessor commandProcessor;

    /**
     * 页脚的排版输入（模型名 / 上下文占比 / 工作目录），主线程每轮 {@code renderFooter} 时刷新。
     * resize 信号处理器在信号线程上只读这几个不可变值重排版，不去碰 conversation——
     * 跨线程读会话历史会与主线程的追加竞争。
     */
    private volatile String footerModel = "";
    private volatile double footerCtxFraction;
    private volatile String footerProjectPath = "";
    /** 页脚当前是否在屏上；resize 重排版不得把已收起的页脚又显示出来。 */
    private volatile boolean footerVisible;

    /**
     * 页脚占的行数：分隔线 + 状态行。{@link #drawFooter()} 推给状态区的行数必须与它一致——
     * 主循环靠这个数把整个输入框沉到屏幕底部，多算一行输入框就会被压进页脚里。
     */
    private static final int FOOTER_ROWS = 2;

    /** 上下文管理门面（每会话一次装配；懒构造，捕获当时 projectRoot） */
    private ContextManager contextManager;

    /** 记忆装配门面（每会话一次）：两级记忆根 + 索引注入文本 + 异步提取调度 */
    private final MemoryManager memoryManager;

    /** 权限沙箱根：生产为当前工作目录；测试可注入 @TempDir 避免文件路径被沙箱拦截。 */
    private Path projectRoot = Path.of(System.getProperty("user.dir"));

    /** MCP 生命周期管理：UI 就位后由 {@link #connectMcp()} 连接并注册工具、退出清理 stdio 子进程。 */
    private McpManager mcpManager;

    /** 启动期降级告警（指令越界/跳过、记忆根不可写/索引截断）：UI 就位后逐行输出 */
    private List<String> startupWarnings = List.of();

    /** 恢复会话后的首轮轮次提醒文本（久未活跃时才有）：下一次 exchange 用掉即清，不进历史 */
    private String pendingTurnReminder;

    public static void run(boolean resume) {
        AppConfig config;
        try {
            config = ConfigLoader.loadDefault();
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return;
        }
        new ConversationController(config, resume).start();
    }

    public ConversationController(AppConfig config, boolean resume) {
        this(buildProvider(config), config, resume);
    }

    /** 包可见：测试可注入假 Provider 驱动 Agent 循环 */
    ConversationController(ChatProvider provider, AppConfig config, boolean resume) {
        this.provider = provider;
        this.config = config;
        Boolean thinkingConfig = config.getThinking();
        boolean thinking = thinkingConfig != null
                ? thinkingConfig : "anthropic".equals(config.getProtocol());
        this.conversation = new Conversation(config.getModel(), thinking, MAX_TOKENS,
                config.getMaxContextTokens());
        this.toolRegistry = new ToolRegistry();
        DefaultToolset.registerAll(toolRegistry);
        toolRegistry.register(new ExitPlanModeTool());
        toolRegistry.register(new AskUserTool());
        // 命令框架装配：一次性注册全部内置命令，注册顺序即帮助与补全的展示顺序
        this.commandRegistry = new CommandRegistry();
        BuiltinCommands.registerAll(commandRegistry);
        this.mcpManager = new McpManager(config, projectRoot);
        // 连接在 start() 里 UI 就位后做：连接耗时（含超时）不该挡在 banner 之前，
        // 告警也要落进输出区而不是裸 stderr
        // 会话目录跟随当前 projectRoot 动态求值（测试可能在装配后再注入 @TempDir）
        this.sessionManager = new SessionManager(SessionStore.forProject(() -> projectRoot), conversation);
        this.sessionManager.setLoader(session -> activateSession(session, "加载"));
        // 两级记忆根同样跟随当前 projectRoot / user.home 动态求值
        this.memoryManager = new MemoryManager(
                new MemoryStore(MemoryScope.project(() -> projectRoot),
                        MemoryScope.user(ConversationController::userHome)),
                conversation, provider, config.isMemoryAutoEnabled());
        this.resume = resume;
        this.renderContext = new RenderContext(config);
        // /clear、加载会话等清空点联动重置上下文管理的运行期状态（冻结记账/熔断；落盘文件不删）
        conversation.addClearHook(this::resetContextStateOnClear);
        initSessionState();
    }

    /** 清空点钩子：上下文管理运行期状态、记忆提取运行态与最近计划落盘位置一并复位（/clear、加载会话联动） */
    private void resetContextStateOnClear() {
        contextManager().reset();
        memoryManager.reset();
        deliveredPlanPath = null;
    }

    /**
     * 会话启动时构建一次 system prompt 并探测环境快照存入会话状态：
     * 每轮 buildRequest 注入（SYSTEM 首位 + 环境 system-reminder 首条），均不进历史；
     * /clear 与恢复/加载会话不清除，下一轮仍注入。
     * 启动告警（指令越界/跳过等）只在 UI 已就位时输出，启动不因记忆设施失败而中断。
     */
    void initSessionState() {
        var instructions = ProjectInstructions.load(projectRoot, userHome());
        startupWarnings = new ArrayList<>(instructions.warnings());
        startupWarnings.addAll(memoryManager.drainWarnings());
        conversation.setSystemPrompt(PromptBuilder.buildSystemPrompt(
                instructions.text(), memoryManager.indexText()));
        conversation.setEnvironment(SystemReminder.environment(EnvironmentDetector.detect(config.getModel())));
        emitStartupWarnings();
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    private void emitStartupWarnings() {
        if (startupWarnings.isEmpty()) {
            return;
        }
        for (String warning : startupWarnings) {
            emitLine(warning);
        }
    }

    /** 提交一行到输出区（进 scrollback + 活跃区历史）；UI 未就位时丢弃。 */
    private void emitLine(String line) {
        if (output == null) {
            return;
        }
        output.appendLine(line);
        liveRenderer().appendCommitted(screenWriter(), line);
    }

    /**
     * 连接并注册 MCP server 工具；由 {@link #start()} 在 banner 输出后调用，
     * 使连接耗时（每 server 默认最多 60s 超时）与告警都在界面就绪之后发生。
     * 单个 server 失败只落一条告警，不中断启动。
     */
    void connectMcp() {
        if (!mcpManager.serverNames().isEmpty()) {
            emitLine("正在连接 MCP server：" + String.join("、", mcpManager.serverNames()) + " …");
        }
        mcpManager.connectAll();
        mcpManager.registerTools(toolRegistry);
        for (String warning : mcpManager.drainWarnings()) {
            emitLine(warning);
        }
    }

    private static ChatProvider buildProvider(AppConfig config) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicProvider(config.getBaseUrl(), config.getApiKey(), config.isTeeEnabled());
            case "openai" -> new OpenAiProvider(config.getBaseUrl(), config.getApiKey(), config.isTeeEnabled());
            default -> throw new ConfigException("不支持的 protocol：" + config.getProtocol());
        };
    }

    private void start() {
        try (AcodeTerminal terminal = AcodeTerminal.open()) {
            this.tui = terminal;
            try {
                this.renderContext.attachTui(terminal);
                this.output = new OutputPane();
                LiveRegionRenderer live = liveRenderer();
                Writer writer = screenWriter();
                output.append(BANNER);
                live.appendCommitted(writer, BANNER);
                output.appendLine("输入 /help 查看命令，/quit 退出");
                live.appendCommitted(writer, "输入 /help 查看命令，/quit 退出");
                // MCP 连接放在 banner 之后：先让用户看到界面，再等外部 server 握手
                connectMcp();
                // 恢复会话后再构建 system 提示：注入的是恢复后的状态（projectRoot 也已定型）
                restoreIfResume();
                initSessionState();
                commandProcessor().mainLoop();
            } finally {
                // Restore the full scrolling region before Terminal.close();
                // otherwise the shell inherits ACode's shortened region.
                renderContext.closeStatus();
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        } finally {
            // /quit 与异常退出都关闭会话句柄并清理 MCP 子进程，避免残留句柄与进程
            closeSession();
            closeMcpManager();
        }
    }

    /** 清理 MCP 连接（stdio 子进程销毁、HTTP 会话释放）；幂等。测试亦可直接调用避免残留子进程。 */
    void closeMcpManager() {
        if (mcpManager != null) {
            mcpManager.closeAll();
        }
    }

    /** 会话管理：装配已在构造期完成，这里只负责首次调用时补绑 UI。 */
    private SessionManager sessionManager() {
        if (!sessionManagerAttached && output != null) {
            sessionManager.attachUi(output, renderContext, tui);
            sessionManagerAttached = true;
        }
        return sessionManager;
    }

    /** --resume 启动：恢复最近活跃的会话；没有则提示（既有文案） */
    void restoreIfResume() {
        if (!resume) {
            return;
        }
        List<Session> sessions = sessionManager().store().list();
        if (sessions.isEmpty()) {
            sessionManager().notice("（没有可恢复的会话）");
            return;
        }
        activateSession(sessions.get(0), "恢复");
    }

    /**
     * 恢复/加载一个会话（恢复四步的落地）：
     * 逐行解析（坏行跳过）→ 消息链截断 → 预算检查（已达触发点则走与 /compact 同一路径压一次）
     * → 时间跨度提醒挂成恢复后首轮的轮次提醒（不进历史、不落盘）。
     *
     * <p>历史用原子替换而非逐条 add（后者会触发逐条落盘）；替换期间抑制重建监听，
     * 否则刚读出来的历史会被当成"压缩重建"原地重写一遍；随后把句柄绑到该会话文件续写。
     */
    private void activateSession(Session session, String action) {
        SessionRecorder recorder = sessionManager().recorder();
        Path file = sessionManager().store().resolve(session.id());
        SessionLoader.Loaded loaded = SessionLoader.load(file);

        recorder.suspend();
        conversation.clear(); // 联动复位上下文管理运行期状态（与 /clear 走同一钩子）
        conversation.replaceAll(loaded.messages());
        recorder.resume();

        recorder.bind(file); // 续写同一文件：不新建、不产生分叉副本
        if (contextManager().executor().needsAutoCompact()) {
            contextManager().executor().run(false);
        }
        loaded.staleReminder().ifPresent(text -> pendingTurnReminder = text);

        sessionManager().renderLoaded(action, session.id(), loaded.messages());
    }

    /**
     * 主循环命令分发：惰性装配（首次使用时以当前 tui/output 构建界面操作接口、
     * 打包命令上下文、注册中心与对话通道交给调度器）。包可见供测试经 handleLine 驱动。
     */
    CommandProcessor commandProcessor() {
        if (commandProcessor == null) {
            UIController ui = new TerminalUIController(output, renderContext,
                    this::handleChat,
                    planMode -> this.planMode = planMode,
                    () -> new UIController.ContextUsage(
                            conversation.estimateContextTokens(), conversation.maxContextTokens()),
                    this::selectMenu,
                    this::clearScreenAndNewSession,
                    this::lastDeliveredPlanPath,
                    () -> this.deliveredPlanPath = null);
            // 上下文工厂：只有 args 每次不同，其余依赖装配时固定打包
            PermissionChecker checker = permissionChecker();
            ContextManager contexts = contextManager();
            SessionManager sessions = sessionManager();
            CommandDispatcher dispatcher = new CommandDispatcher(commandRegistry,
                    args -> new CommandContext(args, ui, checker, contexts, memoryManager,
                            sessions, projectRoot, toolRegistry, VERSION),
                    this::handleChat);
            CommandProcessor processor = new CommandProcessor(tui, sessions, commandRegistry);
            processor.setCommandDispatcher(dispatcher);
            processor.setInputFrame(new CommandProcessor.InputFrame() {
                @Override
                public boolean statusOwnedInput() {
                    return true;
                }

                @Override
                public String mode() {
                    return permissionChecker().mode().configValue();
                }

                @Override
                public String footer() {
                    int max = conversation.maxContextTokens();
                    double fraction = max <= 0 ? 0 : (double) conversation.estimateContextTokens() / max;
                    return StatusBar.infoLine(conversation.model(), fraction, projectRoot.toString(),
                            Math.max(1, renderContext.terminalWidth() - 1));
                }

                @Override
                public void replayHistory() {
                    Writer writer = screenWriter();
                    for (String line : output.lines()) {
                        liveRenderer().appendCommitted(writer, line);
                    }
                }

                @Override
                public List<String> historyLines() {
                    return output.lines();
                }

                @Override
                public void draw() {
                    renderFooter();
                }

                /** 模式行、上边线、输入标记属于同一个 JLine 提示符。 */
                @Override
                public String inputPrompt() {
                    return StatusBar.framedInputPrompt(permissionChecker().mode().configValue(),
                            renderContext.terminalWidth());
                }

                /** 见 {@link #FOOTER_ROWS}：主循环据此把整个输入框沉到屏幕底部。 */
                @Override
                public int footerRows() {
                    return FOOTER_ROWS;
                }

                @Override
                public void resize() {
                    if (footerVisible) {
                        drawFooter();
                    }
                }

                @Override
                public void erase() {
                    footerVisible = false;
                    org.jline.utils.Status status = renderContext.status();
                    if (status != null && status.size() > 0 && tui != null) {
                        tui.terminal().puts(InfoCmp.Capability.cursor_address,
                                Math.max(0, tui.height() - status.size() - 1), 0);
                    }
                    renderContext.hideStatusLines();
                    if (tui != null) {
                        tui.terminal().puts(InfoCmp.Capability.cursor_address,
                                Math.max(0, tui.height() - 1), 0);
                        tui.flush();
                    }
                }
            });
            installResizeRefresh();
            commandProcessor = processor;
        }
        return commandProcessor;
    }

    /**
     * 页脚：分隔线 + 一行状态（模型 · 上下文进度 · 工作目录），画在**提示符下方**的底部常驻
     * 状态区里（JLine {@link org.jline.utils.Status}，见 {@link LiveRegionRenderer#statusOf}）。
     * 模式行与上边线作为 JLine 提示符的前两行绘制。
     * <p>交给 {@code Status} 而不是自己维护光标，是因为那片区域必须让 JLine 一起记账：它算提示符
     * 可用行数时会扣掉状态区的行数，多行输入只会在状态区之上滚动，不会压上来。
     * <p>页脚只进终端、不进 {@link OutputPane}：它是界面装饰而非输出内容，混进去会污染输出日志与
     * 依赖 OutputPane 的断言。
     */
    private void renderFooter() {
        int max = conversation.maxContextTokens();
        footerCtxFraction = max <= 0 ? 0 : (double) conversation.estimateContextTokens() / max;
        footerModel = conversation.model();
        footerProjectPath = projectRoot.toString();
        footerVisible = true;
        drawFooter();
    }

    /** 按缓存的排版输入重排页脚并推到状态区；主线程每轮与 resize 信号处理器共用。 */
    private void drawFooter() {
        int width = renderContext.terminalWidth();
        // Leave the final column unused: writing into the rightmost cell may trigger
        // an implicit wrap in Windows consoles before JLine positions the next row.
        int contentWidth = Math.max(0, width - 1);
        String footer = StatusBar.infoLine(footerModel, footerCtxFraction, footerProjectPath, contentWidth);
        renderContext.updateStatusLines(List.of(StatusBar.divider(contentWidth), footer));
    }

    /**
     * 终端尺寸变化时重建页脚。JLine 自己收得到 SIGWINCH，也会让 {@code Status} 重绘，但那用的是
     * **旧宽度**算出的那行文本——收窄后会被折行或截错，所以要按新宽度重排一遍。
     * <p>处理器只读上面几个缓存值，**不碰 conversation**：信号线程去读会话历史会与主线程的追加竞争。
     * <p>活动 {@code readLine} 期间该应用 handler 会被 JLine 临时替换；输入区自己的 WINCH 路径
     * 会重建提示符，并在 JLine 默认重绘完成后调用输入帧 resize 回调刷新这里的页脚。
     * <p>终端不支持信号（测试路径、dumb 终端）时跳过即可，只是退回「下一轮刷新」这条更慢的路径。
     */
    private void installResizeRefresh() {
        if (tui == null) {
            return;
        }
        try {
            tui.terminal().handle(Terminal.Signal.WINCH, signal -> {
                if (footerVisible) {
                    drawFooter();
                }
            });
        } catch (RuntimeException e) {
            log.debug("注册 SIGWINCH 处理器失败，页脚退回每轮刷新", e);
        }
    }

    /** 弹选择菜单（/resume、/memory 共用）：沿用既有 SelectionMenu overlay 渲染与终端按键源 */
    private int selectMenu(List<MenuEntry> entries, String title) {
        if (menuSelector != null) {
            return menuSelector.apply(entries, title);
        }
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        live.commitRegion(); // 菜单前的活跃区留作历史，菜单从下方空白处画起（同 selectSession 先例）
        return SelectionMenu.of(entries, title, 0)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
    }

    /** /clear 三步语义：清空对话历史（联动清除钩子）→ 收起页脚 → 整屏清空 → 输出区重置 */
    private void clearScreenAndNewSession() {
        conversation.clear();
        footerVisible = false;
        renderContext.hideStatusLines();
        liveRenderer().clearScreen(screenWriter());
        output.clear();
    }

    /** 上下文管理门面：懒装配一次（provider/conversation/工作目录/预算策略）；clear 钩子已挂 conversation */
    private ContextManager contextManager() {
        if (contextManager == null) {
            contextManager = new ContextManager(projectRoot, provider, conversation);
        }
        return contextManager;
    }

    private void handleChat(String input) {
        handleExchange(input, this::ctrlCPressed, () -> { });
    }

    /** 测试用：注入输出面板（真实流程在 start() 中创建） */
    void setOutput(OutputPane output) {
        this.output = output;
    }

    /** 测试用：注入活跃区渲染器（断言流式重绘；真实流程按终端尺寸新建） */
    void setLive(LiveRegionRenderer live) {
        renderContext.setLive(live);
    }

    /** 测试用：注入活跃区输出目标（真实流程用终端 writer） */
    void setScreenWriter(Writer writer) {
        renderContext.setScreenWriter(writer);
    }

    /** 测试用：注入确认应答器（跳过真实终端读行）。 */
    void setConfirmAnswerer(Function<ConfirmationRequestEvent, PermissionResponse> answerer) {
        this.confirmAnswerer = answerer;
    }

    /** 测试用：注入选择应答器（跳过真实终端读键）。 */
    void setChoiceAnswerer(Function<ChoiceRequestEvent, String> answerer) {
        this.choiceAnswerer = answerer;
    }

    /** 测试用：注入选择菜单入口（跳过真实终端按键；/resume、/memory 共用）。 */
    void setMenuSelector(BiFunction<List<MenuEntry>, String, Integer> menuSelector) {
        this.menuSelector = menuSelector;
    }

    /** 测试用：注入权限检查器（供 /permission 命令与执行器装配）。 */
    void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    /** 测试用：注入权限沙箱根（避免 @TempDir 文件路径被 sandbox 拦截）。 */
    void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** 交互应答器：惰性构造，首次调用捕获当前 tui（语义与每次读 tui 字段一致）。 */
    private PromptAnswerer promptAnswerer() {
        if (promptAnswerer == null) {
            promptAnswerer = new PromptAnswerer(tui, renderContext);
        }
        return promptAnswerer;
    }

    /** 活跃区渲染器（委托 RenderContext：测试注入优先、否则按终端尺寸实时新建） */
    private LiveRegionRenderer liveRenderer() {
        return renderContext.liveRenderer();
    }

    /** 活跃区输出目标（委托 RenderContext：测试注入优先、否则终端 writer、tee 诊断） */
    private Writer screenWriter() {
        return renderContext.screenWriter();
    }

    /** 测试用：访问对话历史 */
    Conversation conversation() {
        return conversation;
    }

    /**
     * 单次输入触发 Agent 循环（委托 ExchangeRunner）。ctrlC 注入中断源（真实终端为 Ctrl+C），
     * 便于用 FakeProvider 单测编排。repaint 为保留参数（渲染已全部经活跃区完成）。
     */
    void handleExchange(String input, BooleanSupplier ctrlC, Runnable repaint) {
        ExchangeRunner runner = exchangeRunner();
        // 恢复会话后首轮的轮次提醒：只进请求、不进历史，用掉即清（第二轮不再出现）
        if (pendingTurnReminder != null) {
            runner.setPendingReminder(SystemReminder.wrap(pendingTurnReminder));
            pendingTurnReminder = null;
        }
        Path deliveredPlan = runner.run(input, ctrlC, repaint, planMode);
        if (deliveredPlan != null) {
            this.deliveredPlanPath = deliveredPlan;
        }
    }

    /** 读取最近一次规划交付的计划落盘位置；无交付记录时为 null（供 /do 走有则执行、无则仅切换） */
    Path lastDeliveredPlanPath() {
        return deliveredPlanPath;
    }

    /** 交换执行器：惰性构造，必须晚于全部测试 setter（捕获当时的 output/RenderContext/应答器）。 */
    private ExchangeRunner exchangeRunner() {
        if (exchangeRunner == null) {
            exchangeRunner = new ExchangeRunner(provider, config, conversation, toolRegistry,
                    output, renderContext, confirmAnswerer, choiceAnswerer, projectRoot, this::permissionChecker);
            exchangeRunner.setContextManager(contextManager());
            exchangeRunner.setMemoryManager(memoryManager);
        }
        return exchangeRunner;
    }

    /** 选择菜单入口（/resume、/memory 共用）；测试可注入替身跳过真实终端按键。 */
    private BiFunction<List<MenuEntry>, String, Integer> menuSelector;

    /** 权限检查器：懒构建（与 /permission 共用，经 Supplier 传入 ExchangeRunner）。 */
    PermissionChecker permissionChecker() {
        if (permissionChecker == null) {
            permissionChecker = buildPermissionChecker();
        }
        return permissionChecker;
    }

    /** 装配权限检查器：模式取 config（缺省 default），沙箱根为项目根，规则文件沿用 .acode/ 命名空间。 */
    private PermissionChecker buildPermissionChecker() {
        PermissionMode mode = PermissionMode.fromConfig(config.getPermissionMode());
        if (mode == null) {
            mode = PermissionMode.DEFAULT;
        }
        RuleEngine ruleEngine = new RuleEngine(
                Path.of(System.getProperty("user.home")).resolve(".acode/permissions.yaml"),
                projectRoot.resolve(".acode/permissions.yaml"),
                projectRoot.resolve(".acode/permissions.local.yaml"));
        // 两级记忆根作为额外允许根传入沙箱：用户级在项目外，是既有设计的盲区
        return new PermissionChecker(mode, projectRoot, ruleEngine,
                List.of(memoryManager.store().projectScope().root(),
                        memoryManager.store().userScope().root()));
    }

    /** raw 模式下检测 Ctrl+C（0x03 字节）；命中则消费该字节。无终端（纯测试环境）不检测。 */
    private boolean ctrlCPressed() {
        if (tui == null) {
            return false;
        }
        try {
            if (tui.terminal().reader().peek(10) == 0x03) {
                tui.terminal().reader().read(0);
                return true;
            }
        } catch (IOException e) {
            // 读取失败视为未按下
        }
        return false;
    }

    /** 退出路径：关闭活跃会话句柄（不整存、不新建文件）。 */
    void closeSession() {
        sessionManager().closeSession();
    }

    /** 测试用：访问记忆装配门面 */
    MemoryManager memoryManager() {
        return memoryManager;
    }
}
