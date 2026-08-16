package com.acode;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.agent.EventConfirmationGate;
import com.acode.config.AppConfig;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.config.ConfigValidator;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ContentBlock;
import com.acode.provider.ProviderException;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.anthropic.AnthropicProvider;
import com.acode.provider.openai.OpenAiProvider;
import com.acode.session.Session;
import com.acode.session.SessionStore;
import com.acode.tool.DefaultToolset;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.CommandRouter;
import com.acode.ui.ConfirmationPrompt;
import com.acode.ui.InputPane;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.StreamPrinter;
import com.acode.ui.ToolCallDisplay;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * T12 主循环与装配：配置 → Provider → 会话 → TUI 串成完整对话。
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

    private final ChatProvider provider;
    private final AppConfig config;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;
    private final boolean resume;

    /** plan 模式开关：/plan 进入、/do 退出；作用于下一次 exchange 新建的 Agent */
    private boolean planMode = false;

    private AcodeTerminal tui;
    private OutputPane output;
    private LiveRegionRenderer live;
    private Writer screenWriter;

    /** 主循环输入面板：确认提示经它读行（仅主线程触碰终端，agent 线程安全）。 */
    private InputPane inputPane;

    /** 确认应答器：收到 ConfirmationRequestEvent 后渲染提示并返回批准与否；测试可注入替身。 */
    private Function<ConfirmationRequestEvent, Boolean> confirmAnswerer = this::answerConfirmationPrompt;

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
        boolean thinking = "anthropic".equals(config.getProtocol());
        this.conversation = new Conversation(config.getModel(), thinking, MAX_TOKENS,
                config.getMaxContextTokens());
        this.toolRegistry = new ToolRegistry();
        DefaultToolset.registerAll(toolRegistry);
        this.sessionStore = new SessionStore(SessionStore.defaultDir());
        this.resume = resume;
    }

    private static ChatProvider buildProvider(AppConfig config) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicProvider(config.getBaseUrl(), config.getApiKey());
            case "openai" -> new OpenAiProvider(config.getBaseUrl(), config.getApiKey(), config.isTeeEnabled());
            default -> throw new ConfigException("不支持的 protocol：" + config.getProtocol());
        };
    }

    private void start() {
        try (AcodeTerminal terminal = AcodeTerminal.open()) {
            this.tui = terminal;
            this.output = new OutputPane();
            LiveRegionRenderer live = liveRenderer();
            Writer writer = screenWriter();
            output.append(BANNER);
            live.appendCommitted(writer, BANNER);
            output.appendLine("输入 /help 查看命令，/quit 退出");
            live.appendCommitted(writer, "输入 /help 查看命令，/quit 退出");
            restoreIfResume();
            mainLoop();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        }
    }

    private void restoreIfResume() {
        if (!resume) {
            return;
        }
        Optional<Session> latest = sessionStore.readLatest();
        if (latest.isEmpty()) {
            output.appendLine("（没有可恢复的会话）");
            liveRenderer().appendCommitted(screenWriter(), "（没有可恢复的会话）");
            return;
        }
        Session session = latest.get();
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message, live, writer);
        }
        output.appendLine("（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        live.appendCommitted(writer, "（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
    }

    private void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), "> ");
        this.inputPane = input;
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        while (true) {
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
                saveSession();
                return;
            }
            switch (CommandRouter.route(line)) {
                case QUIT -> {
                    saveSession();
                    return;
                }
                case CLEAR -> {
                    conversation.clear();
                    output.clear();
                    output.appendLine("（已清空）");
                    live.appendCommitted(writer, "（已清空）");
                }
                case HELP -> {
                    output.append(CommandRouter.HELP_TEXT);
                    live.appendCommitted(writer, CommandRouter.HELP_TEXT);
                }
                case RESUME -> selectSession();
                case PLAN -> {
                    planMode = true;
                    output.appendLine("（已进入规划模式：只读探索，计划落盘到 .acode/plans/）");
                    live.appendCommitted(writer, "（已进入规划模式：只读探索，计划落盘到 .acode/plans/）");
                }
                case DO -> {
                    planMode = false;
                    output.appendLine("（已退出规划模式，开始执行）");
                    live.appendCommitted(writer, "（已退出规划模式，开始执行）");
                }
                case SKIP -> {
                    // 空白输入，忽略
                }
                case CHAT -> handleChat(line);
            }
        }
    }

    private static final int KEY_NONE = 0;
    private static final int KEY_UP = 1;
    private static final int KEY_DOWN = 2;
    private static final int KEY_ENTER = 3;
    private static final int KEY_CANCEL = 4;

    /**
     * /resume：列出历史会话，↑/↓ 选择、回车加载、Esc 取消。
     * 菜单作为活跃区 overlay 渲染：只重绘屏幕底部、不进回滚；选定/取消后清掉菜单，历史再追加。
     */
    private void selectSession() {
        List<Session> sessions = sessionStore.list();
        if (sessions.isEmpty()) {
            output.appendLine("（没有可恢复的会话）");
            liveRenderer().appendCommitted(screenWriter(), "（没有可恢复的会话）");
            return;
        }
        drainPendingInput();
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        live.commitRegion(); // 上次活跃区已留在屏上作历史，菜单从下方空白处画起
        int selected = sessions.size() - 1;
        while (true) {
            live.redraw(writer, menuLines(sessions, selected));
            switch (readMenuKey()) {
                case KEY_UP -> selected = (selected - 1 + sessions.size()) % sessions.size();
                case KEY_DOWN -> selected = (selected + 1) % sessions.size();
                case KEY_ENTER -> {
                    live.clear(writer);
                    loadSession(sessions.get(selected));
                    return;
                }
                case KEY_CANCEL -> {
                    live.clear(writer);
                    output.appendLine("（已取消）");
                    live.appendCommitted(writer, "（已取消）");
                    return;
                }
                default -> {
                    // 忽略无关按键，保持菜单
                }
            }
        }
    }

    /** 菜单渲染行：提示 + 逐会话条目（选中行反显），供活跃区 overlay 使用。 */
    private List<String> menuLines(List<Session> sessions, int selected) {
        List<String> lines = new ArrayList<>();
        lines.add("（↑/↓ 选择会话，回车加载，Esc 取消）");
        for (int i = 0; i < sessions.size(); i++) {
            lines.add(menuLine(sessions.get(i), i == selected));
        }
        return lines;
    }

    /** 单条会话菜单行：时间戳 + 消息数 + 首条用户消息预览；选中行反显。 */
    private static String menuLine(Session session, boolean selected) {
        String body = session.getId() + "  " + session.getMessages().size() + " 条 · " + preview(session);
        return selected ? "\033[7m▸ " + body + "\033[0m" : "  " + body;
    }

    private static String preview(Session session) {
        for (ChatMessage m : session.getMessages()) {
            if (m.role() == ChatMessage.Role.USER) {
                String text = m.content().replace('\n', ' ').trim();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        return "（无用户消息）";
    }

    /**
     * 直接读终端键：方向键/回车/Esc 分别归类；Ctrl+C 视为取消。
     * 方向键存在两种序列：CSI 模式 `\033[A` 与 SS3 模式 `\033OA`（JLine 进入应用光标模式后常见），都要识别。
     */
    private int readMenuKey() {
        try {
            NonBlockingReader reader = tui.terminal().reader();
            int c = reader.read();
            if (c == '\r' || c == '\n') {
                return KEY_ENTER;
            }
            if (c == 0x03) {
                return KEY_CANCEL;
            }
            if (c == 0x1b) {
                int next = reader.peek(50);
                if (next == '[' || next == 'O') {
                    reader.read(0); // 消费 '[' 或 'O'
                    int ch = reader.read(50);
                    if (ch == 'A') {
                        return KEY_UP;
                    }
                    if (ch == 'B') {
                        return KEY_DOWN;
                    }
                    return KEY_NONE;
                }
                log.info("菜单取消：裸 ESC（跟随字节 0x{}）", Integer.toHexString(next));
                return KEY_CANCEL; // 裸 Esc
            }
            log.info("菜单忽略未知键：0x{}", Integer.toHexString(c));
            return KEY_NONE;
        } catch (IOException e) {
            return KEY_NONE;
        }
    }

    /**
     * 排空 readLine 返回后共享 reader 中残留的字节（如 Windows Enter 的 \r\n 里未消费的 \n），
     * 避免被菜单误判为按键导致立即取消/加载。
     */
    private void drainPendingInput() {
        try {
            NonBlockingReader reader = tui.terminal().reader();
            long deadline = System.currentTimeMillis() + 50;
            int drained = 0;
            while (System.currentTimeMillis() < deadline) {
                if (reader.peek(5) == NonBlockingReader.READ_EXPIRED) {
                    break;
                }
                reader.read(0);
                drained++;
            }
            if (drained > 0) {
                log.info("进菜单前排空残留输入 {} 字节", drained);
            }
        } catch (IOException e) {
            // 读取失败视为无残留
        }
    }

    /** 用某个会话的历史替换当前对话：回滚为 append-only，历史经追加式渲染进回滚（不重复打印 banner）。 */
    private void loadSession(Session session) {
        conversation.clear();
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        output.appendLine("（已加载会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        live.appendCommitted(writer, "（已加载会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message, live, writer);
        }
    }

    /** 把一条历史消息渲染进回滚：内容模型 + 活跃区追加式写屏，工具块压缩为单行摘要。 */
    private void appendHistoryMessage(ChatMessage message, LiveRegionRenderer live, Writer writer) {
        String rendered = renderHistoryMessage(message);
        if (rendered.isEmpty()) {
            return;
        }
        if (message.role() == ChatMessage.Role.USER) {
            output.append("● " + rendered + "\n");
            live.appendCommitted(writer, "● " + rendered);
        } else {
            output.append(rendered);
            live.appendCommitted(writer, rendered);
        }
    }

    /** 消息渲染为文本：text 块原样拼接，tool_use / tool_result 压缩为单行摘要。 */
    static String renderHistoryMessage(ChatMessage message) {
        StringBuilder text = new StringBuilder();
        List<String> extras = new ArrayList<>();
        for (ContentBlock block : message.blocks()) {
            switch (block) {
                case TextBlock t -> text.append(t.text());
                case ToolUseBlock tu -> {
                    String params = ToolCallDisplay.summarizeParams(tu.input());
                    extras.add("[工具调用 " + tu.name()
                            + (params.isEmpty() ? "" : "(" + params + ")") + "]");
                }
                case ToolResultBlock tr -> {
                    String summary = collapseOneLine(tr.content(), 80);
                    extras.add("[工具结果 " + (tr.isError() ? "失败" : "成功")
                            + (summary.isEmpty() ? "" : "：" + summary) + "]");
                }
            }
        }
        if (text.isEmpty() && extras.isEmpty()) {
            return "";
        }
        String result = text.toString();
        if (!extras.isEmpty()) {
            if (!result.isEmpty()) {
                result += "\n";
            }
            result += String.join("\n", extras);
        }
        return result;
    }

    /** 多行/超长文本压缩为单行摘要（恢复会话显示用）。 */
    private static String collapseOneLine(String text, int max) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
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
        this.live = live;
    }

    /** 测试用：注入活跃区输出目标（真实流程用终端 writer） */
    void setScreenWriter(Writer writer) {
        this.screenWriter = writer;
    }

    /** 测试用：注入确认应答器（跳过真实终端读行）。 */
    void setConfirmAnswerer(Function<ConfirmationRequestEvent, Boolean> answerer) {
        this.confirmAnswerer = answerer;
    }

    /** 默认确认应答：渲染「要执行 X …？[y/n]」并读一行；无输入面板（纯测试环境）视为拒绝。 */
    private boolean answerConfirmationPrompt(ConfirmationRequestEvent event) {
        if (inputPane == null) {
            return false;
        }
        ConfirmationPrompt prompt = new ConfirmationPrompt(inputPane::readLine, liveRenderer(), screenWriter());
        return prompt.ask(event.toolName(), event.argsSummary());
    }

    /** 活跃区渲染器：测试注入优先，否则按终端尺寸实时新建（窗口变化随读随取）。 */
    private LiveRegionRenderer liveRenderer() {
        if (live != null) {
            return live;
        }
        if (tui != null) {
            return new LiveRegionRenderer(tui::width, tui::height);
        }
        return new LiveRegionRenderer(80, 24);
    }

    /** 活跃区输出目标：测试注入优先，否则用终端 writer；无终端时丢弃到 StringWriter。 */
    private Writer screenWriter() {
        if (screenWriter != null) {
            return screenWriter;
        }
        if (tui != null) {
            Writer w = tui.terminal().writer();
            if (!config.isTeeEnabled()) {
                return w;
            }
            TeeWriter tw = new TeeWriter(w);
            try {
                tw.logOnly("\n== ACODE TEE w=" + tui.width() + " h=" + tui.height() + " ==\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return tw;
        }
        return new StringWriter();
    }

    /** 诊断用：把写进终端的每个字节按原样追加到日志文件（含 ANSI 与 \r\n），tee 开关控制。 */
    private static final class TeeWriter extends Writer {
        private final Writer target;
        private final BufferedWriter log;

        TeeWriter(Writer target) {
            this.target = target;
            try {
                this.log = new BufferedWriter(new java.io.FileWriter("acode-terminal.log", true));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            target.write(cbuf, off, len);
            log.write("[" + Thread.currentThread().getName() + "]");
            log.write(cbuf, off, len);
            log.flush();
        }

        /** 只写日志文件、不进终端（诊断头信息用）。 */
        void logOnly(String s) throws IOException {
            log.write(s);
            log.flush();
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }

    /** 测试用：访问对话历史 */
    Conversation conversation() {
        return conversation;
    }

    /**
     * 单次输入触发 Agent 循环：追加 user 消息 → new Agent(...).run() 在虚拟线程跑 ReAct 循环 →
     * 主线程订阅事件队列逐条渲染（流式文本 / 工具卡片 / 轮次收尾 / 重试 / 错误 / 循环结束提示）。
     * ctrlC 注入中断源（真实终端为 Ctrl+C），便于用 FakeProvider 单测编排。
     * repaint 为保留参数：渲染已全部经活跃区完成，测试沿用传 no-op 的签名。
     */
    void handleExchange(String input, BooleanSupplier ctrlC, Runnable repaint) {
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, input));
        output.append("● " + input + "\n");
        LiveRegionRenderer live = liveRenderer();
        Writer writer = screenWriter();
        live.commitRegion(); // 上一轮活跃区已留在屏上作历史，本轮菜单重绘状态归零
        live.appendCommitted(writer, "● " + input);

        Agent agent = new Agent(provider, conversation, toolRegistry,
                new ToolContext(Path.of(System.getProperty("user.dir"))), maxIterations());
        agent.setPlanMode(planMode);
        agent.setConfirmationGate(new EventConfirmationGate());
        BlockingQueue<AgentEvent> events = agent.run();

        StreamPrinter printer = new StreamPrinter(output, live, writer, config.isTeeEnabled());
        List<ToolResult> turnResults = new ArrayList<>();
        while (true) {
            if (ctrlC.getAsBoolean()) {
                agent.cancel();
                printer.finishTurn(); // 半截 footer 先转正进回滚，中断提示再追加
                output.appendLine("（已中断）");
                live.appendCommitted(writer, "（已中断）");
                awaitLoopEnd(agent); // 取消不吐 LoopComplete：等循环线程收尾（补「已取消」）再返回
                break;
            }
            AgentEvent event = pollEvent(events);
            if (event == null) {
                if (!agent.isRunning() && events.isEmpty()) {
                    break; // 取消等无 LoopComplete 收尾：循环线程结束且事件耗尽即结束
                }
                continue;
            }
            if (event instanceof LoopComplete) {
                printer.updateToolCalls(turnResults);
                printer.finishTurn();
                completeLoop(agent, live, writer);
                break;
            } else if (event instanceof StreamText streamText) {
                printer.onDelta(streamText.text());
            } else if (event instanceof ToolUseEvent toolUse) {
                printer.onToolUse(new ToolUseBlock(toolUse.toolId(), toolUse.toolName(), toolUse.args()));
            } else if (event instanceof ToolResultEvent toolResult) {
                turnResults.add(toolResult.isError()
                        ? ToolResult.failure(toolResult.output())
                        : ToolResult.success(toolResult.output()));
            } else if (event instanceof TurnComplete) {
                printer.updateToolCalls(turnResults);
                printer.finishTurn(); // 本轮文本与卡片转正进回滚，下一轮从下方开始
                turnResults = new ArrayList<>();
                printer = new StreamPrinter(output, live, writer, config.isTeeEnabled());
            } else if (event instanceof RetryEvent retry) {
                output.appendLine("（重试中：" + retry.reason() + "）");
                live.appendCommitted(writer, "（重试中：" + retry.reason() + "）");
            } else if (event instanceof ErrorEvent error) {
                printer.onError(new ProviderException(error.message()));
            } else if (event instanceof ConfirmationRequestEvent confirm) {
                confirm.response().answer(confirmAnswerer.apply(confirm));
            }
        }
    }

    /** 循环轮数上限：配置缺失时用默认值（与 ConfigValidator 一致） */
    private int maxIterations() {
        Integer configured = config.getMaxIterations();
        return configured != null && configured > 0 ? configured : ConfigValidator.DEFAULT_MAX_ITERATIONS;
    }

    /** 事件轮询：20ms 超时；中断恢复中断位并返回 null */
    private static AgentEvent pollEvent(BlockingQueue<AgentEvent> events) {
        try {
            return events.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 取消后等循环线程收尾（上限 5 秒），避免与下一次 exchange 并发写历史 */
    private static void awaitLoopEnd(Agent agent) {
        long deadline = System.currentTimeMillis() + 5000;
        while (agent.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 循环收尾：按终止原因补提示（MAX_ITERATIONS / PLAN_DELIVERED / CANCELED / ERROR） */
    private void completeLoop(Agent agent, LiveRegionRenderer live, Writer writer) {
        switch (agent.termination()) {
            case MAX_ITERATIONS -> {
                output.appendLine("（达到最大轮数，已停止执行）");
                live.appendCommitted(writer, "（达到最大轮数，已停止执行）");
            }
            case PLAN_DELIVERED -> {
                output.appendLine("（计划已交付）");
                live.appendCommitted(writer, "（计划已交付）");
                Path plan = agent.planPath();
                if (plan != null) {
                    try {
                        String content = Files.readString(plan);
                        output.append(content);
                        live.appendCommitted(writer, content);
                    } catch (IOException e) {
                        log.warn("读取计划文件失败：{}", e.getMessage());
                    }
                }
                output.appendLine("输入 /do 退出 plan 模式开始执行");
                live.appendCommitted(writer, "输入 /do 退出 plan 模式开始执行");
            }
            case CANCELED -> {
                output.appendLine("（已中断）");
                live.appendCommitted(writer, "（已中断）");
            }
            case ERROR -> { /* ErrorEvent 已输出错误行，无需重复 */ }
            case NORMAL -> { /* 自然收尾，无提示 */ }
        }
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
        if (conversation.messageCount() == 0) {
            return;
        }
        try {
            sessionStore.save(new Session(null, System.currentTimeMillis(), conversation.history()));
        } catch (RuntimeException e) {
            log.warn("保存会话失败：{}", e.getMessage());
        }
    }
}
