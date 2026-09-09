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
import com.acode.config.AppConfig;
import com.acode.config.CCSwitchConfig;
import com.acode.config.CCSwitchConfigReader;
import com.acode.config.CCSwitchWatcher;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.config.ConfigValidator;
import com.acode.conversation.Conversation;
import com.acode.mcp.McpManager;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionResponse;
import com.acode.permission.RuleEngine;
import com.acode.prompt.EnvironmentDetector;
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
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.DefaultToolset;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.CommandRouter;
import com.acode.ui.ConfirmationPrompt;
import com.acode.ui.HistoryRenderer;
import com.acode.ui.InputPane;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.PromptAnswerer;
import com.acode.ui.RenderContext;
import com.acode.ui.SelectionMenu;
import com.acode.ui.StreamPrinter;
import com.acode.ui.TerminalMenuKeySource;
import com.acode.ui.ToolCallDisplay;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
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

    private static final String BANNER = """
             ___   ____    ___   ___   ____
            / _ \\ / ___|  / _ \\ / _ \\ |  _ \\
           | | | | |     | | | | | | || | | |
           | |_| | |___  | |_| | |_| || |_| |
            \\___/ \\____|  \\___/ \\___/ |____/
                          ACode v0.1.0
            """;

    private volatile ChatProvider provider;
    private volatile AppConfig config;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final boolean resume;
    private boolean sessionManagerAttached;
    private CCSwitchWatcher ccSwitchWatcher;

    /** plan 模式开关：/plan 进入、/do 退出；作用于下一次 exchange 新建的 Agent */
    private boolean planMode = false;

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

    /** 权限检查器：在 handleExchange 装配注入；/permission-mode 命令即时切档用。 */
    private PermissionChecker permissionChecker;

    private ExchangeRunner exchangeRunner;
    private CommandProcessor commandProcessor;

    /** 权限沙箱根：生产为当前工作目录；测试可注入 @TempDir 避免文件路径被沙箱拦截。 */
    private Path projectRoot = Path.of(System.getProperty("user.dir"));

    /** MCP 生命周期管理：启动连接并注册工具、退出清理 stdio 子进程；未配置 mcp_servers 时为空 manager。 */
    private McpManager mcpManager;

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
        this.mcpManager = new McpManager(config, projectRoot);
        this.mcpManager.connectAll();
        this.mcpManager.registerTools(toolRegistry);
        this.sessionManager = new SessionManager(new SessionStore(SessionStore.defaultDir()), conversation);
        this.resume = resume;
        this.renderContext = new RenderContext(config);
        initSessionState();
    }

    /**
     * 会话启动时构建一次 system prompt 并探测环境快照存入会话状态：
     * 每轮 buildRequest 注入（SYSTEM 首位 + 环境 system-reminder 首条），均不进历史；
     * /clear 与恢复/加载会话不清除，下一轮仍注入。
     */
    private void initSessionState() {
        conversation.setSystemPrompt(PromptBuilder.buildSystemPrompt());
        conversation.setEnvironment(SystemReminder.environment(EnvironmentDetector.detect(config.getModel())));
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
            this.renderContext.attachTui(terminal);
            this.output = new OutputPane();
            LiveRegionRenderer live = liveRenderer();
            Writer writer = screenWriter();
            output.append(BANNER);
            live.appendCommitted(writer, BANNER);
            if (config.isCcSwitchDetected()) {
                String msg = "已自动检测 CC Switch 配置（代理: " + config.getBaseUrl() + "）";
                output.appendLine(msg);
                live.appendCommitted(writer, msg);
                startCCSwitchWatcher();
            }
            int width = tui.width();
            String div = com.acode.ui.StatusBar.divider(width);
            output.appendLine(div);
            live.appendCommitted(writer, div);
            String hint = "输入 /help 查看命令，/quit 退出";
            output.appendLine(hint);
            live.appendCommitted(writer, hint);
            // 恢复会话内容须在等待帧之前提交，否则会写在预留提示符行上、冲掉页脚
            restoreIfResume();
            // 初始等待帧：模式提示 + 分隔线（提示符上方）+ 页脚分隔线 + 模型信息（提示符下方）
            String modeName = config.getPermissionMode() != null ? config.getPermissionMode() : "default";
            PermissionMode mode = PermissionMode.fromConfig(modeName);
            if (mode == null) mode = PermissionMode.DEFAULT;
            renderWaitingFrame(live, writer, mode);
            CommandProcessor cp = commandProcessor();
            cp.setFramePrinter(() -> renderWaitingFrame(live, writer, permissionChecker().mode()));
            cp.mainLoop();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        } finally {
            stopCCSwitchWatcher();
            closeMcpManager();
        }
    }

    /** 渲染等待输入帧：模式提示 + 分隔线（提示符上方）+ 页脚分隔线 + 模型信息（提示符下方）。
     *  光标停在预留提示符行，由 JLine 绘制 >*；供启动与 /clear 后重绘复用。 */
    private void renderWaitingFrame(LiveRegionRenderer live, Writer writer, PermissionMode mode) {
        int width = tui.width();
        String modeHint = com.acode.ui.StatusBar.modeLine(
                mode.configValue(), com.acode.ui.StatusBar.nextModeName(mode), width);
        String divider = com.acode.ui.StatusBar.divider(width);
        String model = conversation.getModel();
        double ctxFraction = conversation.contextUsageFraction();
        String footerModel = com.acode.ui.StatusBar.infoLine(model, ctxFraction, projectRoot.toString(), width);
        output.appendLine(modeHint);
        output.appendLine(divider);
        output.appendLine(divider);
        output.appendLine(footerModel);
        live.renderWaitingFrame(writer, modeHint, divider, divider, footerModel);
    }

    /** 清理 MCP 连接（stdio 子进程销毁、HTTP 会话释放）；幂等。测试亦可直接调用避免残留子进程。 */
    void closeMcpManager() {
        if (mcpManager != null) {
            mcpManager.closeAll();
        }
    }

    private void startCCSwitchWatcher() {
        ccSwitchWatcher = CCSwitchWatcher.startDefault(this::reloadCCSwitchConfig);
    }

    private void stopCCSwitchWatcher() {
        if (ccSwitchWatcher != null) {
            ccSwitchWatcher.stop();
        }
    }

    void reloadCCSwitchConfig() {
        try {
            CCSwitchConfig newConfig = CCSwitchConfigReader.read().orElse(null);
            if (newConfig == null) {
                log.warn("CC Switch 配置文件已删除或不可读，保留当前配置");
                if (output != null) {
                    output.appendLine("CC Switch 配置已不可用，保留当前配置");
                }
                return;
            }

            String oldBaseUrl = config.getBaseUrl();
            Path global = Path.of(System.getProperty("user.home"), ".acode/config.yaml");
            Path projectDir = Path.of("").toAbsolutePath();
            AppConfig reloaded = ConfigLoader.reloadWithCCSwitch(global, projectDir, newConfig);

            boolean providerChanged = !reloaded.getBaseUrl().equals(oldBaseUrl)
                    || !reloaded.getApiKey().equals(config.getApiKey())
                    || !reloaded.getProtocol().equals(config.getProtocol());

            this.config = reloaded;
            if (providerChanged) {
                this.provider = buildProvider(reloaded);
                this.exchangeRunner = null;
            }

            if (output != null) {
                output.appendLine("CC Switch 配置已更新（代理: " + reloaded.getBaseUrl() + "）");
            }
        } catch (Exception e) {
            log.warn("CC Switch 配置热更新失败，保留当前配置", e);
            if (output != null) {
                output.appendLine("CC Switch 配置热更新失败，保留当前配置");
            }
        }
    }

    /** 会话管理：懒绑定 UI（首次使用时以当前 output/RenderContext/tui 装配）。 */
    private SessionManager sessionManager() {
        if (!sessionManagerAttached) {
            sessionManager.attachUi(output, renderContext, tui);
            sessionManagerAttached = true;
        }
        return sessionManager;
    }

    private void restoreIfResume() {
        sessionManager().restoreIfResume(resume);
    }

    /** 主循环命令分发：惰性构造（首次使用时以当前 tui/output/应答器装配）。 */
    private CommandProcessor commandProcessor() {
        if (commandProcessor == null) {
            commandProcessor = new CommandProcessor(tui, output, renderContext, conversation,
                    sessionManager(), this::permissionChecker, this::handleChat,
                    planMode -> this.planMode = planMode,
                    this::modelOptions, this::switchModel);
        }
        return commandProcessor;
    }

    /** /permission-mode 切档（委托 CommandProcessor；测试直接调用）。 */
    void handlePermissionMode(String arg, LiveRegionRenderer live, Writer writer) {
        commandProcessor().handlePermissionMode(arg, live, writer);
    }

    /** /model 切模型（委托 CommandProcessor；测试直接调用）。 */
    void handleModel(String arg, LiveRegionRenderer live, Writer writer) {
        commandProcessor().handleModel(arg, live, writer);
    }

    /**
     * /resume：列出历史会话，↑/↓ 选择、回车加载、Esc 取消。
     * 菜单作为活跃区 overlay 渲染：只重绘屏幕底部、不进回滚；选定/取消后清掉菜单，历史再追加。
     */
    private void selectSession() {
        sessionManager().selectSession();
    }

    private void loadSession(Session session) {
        sessionManager().loadSession(session);
    }

    private void handleChat(String input) {
        handleExchange(input, this::ctrlCPressed, () -> { });
    }

    /**
     * /model 菜单数据：从 CC Switch 模型映射构建选项列表。
     * 只取 tier 名称键（如 opus/sonnet/haiku），跳过 Claude 全名键（含 claude- 前缀）。
     * 格式："tier → actualModel"，无 CC Switch 时返回空列表。
     */
    List<String> modelOptions() {
        CCSwitchConfig ccSwitch = config.getCcSwitchConfig();
        if (ccSwitch == null) {
            return List.of();
        }
        Map<String, String> mapping = ccSwitch.modelMapping();
        if (mapping.isEmpty()) {
            return List.of();
        }
        Map<String, String> tierOnly = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String key = entry.getKey();
            if (!key.contains("claude") && !key.contains("-")) {
                tierOnly.put(key, entry.getValue());
            }
        }
        if (tierOnly.isEmpty()) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : tierOnly.entrySet()) {
            options.add(entry.getKey() + "  →  " + entry.getValue());
        }
        return options;
    }

    /**
     * /model 切换模型：更新 conversation、config 和环境提醒，清空 exchangeRunner 懒重建。
     */
    void switchModel(String newModel) {
        conversation.setModel(newModel);
        config.setModel(newModel);
        conversation.setEnvironment(SystemReminder.environment(EnvironmentDetector.detect(newModel)));
        this.exchangeRunner = null;
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

    /** 测试用：注入权限检查器（供 /permission-mode 命令与执行器装配）。 */
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
        exchangeRunner().run(input, ctrlC, repaint, planMode);
    }

    /** 交换执行器：惰性构造，必须晚于全部测试 setter（捕获当时的 output/RenderContext/应答器）。 */
    private ExchangeRunner exchangeRunner() {
        if (exchangeRunner == null) {
            exchangeRunner = new ExchangeRunner(provider, config, conversation, toolRegistry,
                    output, renderContext, confirmAnswerer, choiceAnswerer, projectRoot, this::permissionChecker);
        }
        return exchangeRunner;
    }

    /** 权限检查器：懒构建（与 /permission-mode 共用，经 Supplier 传入 ExchangeRunner）。 */
    private PermissionChecker permissionChecker() {
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
        return new PermissionChecker(mode, projectRoot, ruleEngine);
    }

    /** raw 模式下检测 Ctrl+C（0x03 字节）；命中则消费该字节。 */
    private boolean ctrlCPressed() {
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

    /** 退出时把完整历史存为新会话文件；空会话不存。 */
    private void saveSession() {
        sessionManager().saveSession();
    }
}
