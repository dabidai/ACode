package com.acode;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.config.AppConfig;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.config.ConfigValidator;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ContentBlock;
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
import com.acode.ui.InputPane;
import com.acode.ui.OutputPane;
import com.acode.ui.StreamPrinter;
import com.acode.ui.ToolCallDisplay;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
            case "openai" -> new OpenAiProvider(config.getBaseUrl(), config.getApiKey());
            default -> throw new ConfigException("不支持的 protocol：" + config.getProtocol());
        };
    }

    private void start() {
        try (AcodeTerminal terminal = AcodeTerminal.open()) {
            this.tui = terminal;
            this.output = new OutputPane();
            output.append(BANNER);
            output.appendLine("输入 /help 查看命令，/quit 退出");
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
            return;
        }
        Session session = latest.get();
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message);
        }
        output.appendLine("（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
    }

    private void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), "> ", new InputPane.ScrollHandler() {
            @Override
            public void scroll(int delta) {
                output.scrollBy(delta);
                tui.repaintOutputArea(output);
            }

            @Override
            public void scrollToY(int y) {
                tui.scrollToMouseY(output, y);
                tui.repaintOutputArea(output);
            }
        });
        while (true) {
            tui.repaint(output);
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
                saveSession();
                return;
            }
            // JLine 回车会物理滚动终端（旧分隔线残影残留），清 shadow 让下次 repaint 全量重绘擦掉
            tui.invalidateShadow();
            switch (CommandRouter.route(line)) {
                case QUIT -> {
                    saveSession();
                    return;
                }
                case CLEAR -> {
                    output.clear();
                    output.resetScroll();
                    conversation.clear();
                    output.appendLine("（已清空）");
                }
                case HELP -> output.append(CommandRouter.HELP_TEXT);
                case RESUME -> selectSession();
                case PLAN -> {
                    planMode = true;
                    output.appendLine("（已进入规划模式：只读探索，计划落盘到 .acode/plans/）");
                }
                case DO -> {
                    planMode = false;
                    output.appendLine("（已退出规划模式，开始执行）");
                }
                case SKIP -> {
                    // 空白输入，仅重绘
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
     * 菜单以输出区尾部块呈现，每次按键移除旧块重画，不污染历史消息。
     */
    private void selectSession() {
        List<Session> sessions = sessionStore.list();
        if (sessions.isEmpty()) {
            output.appendLine("（没有可恢复的会话）");
            return;
        }
        drainPendingInput();
        int selected = sessions.size() - 1;
        int menuStart = output.lineCount();
        while (true) {
            output.removeLast(output.lineCount() - menuStart);
            output.appendLine("（↑/↓ 选择会话，回车加载，Esc 取消）");
            for (int i = 0; i < sessions.size(); i++) {
                output.appendLine(menuLine(sessions.get(i), i == selected));
            }
            tui.repaint(output);
            switch (readMenuKey()) {
                case KEY_UP -> selected = (selected - 1 + sessions.size()) % sessions.size();
                case KEY_DOWN -> selected = (selected + 1) % sessions.size();
                case KEY_ENTER -> {
                    output.removeLast(output.lineCount() - menuStart);
                    loadSession(sessions.get(selected));
                    return;
                }
                case KEY_CANCEL -> {
                    output.removeLast(output.lineCount() - menuStart);
                    output.appendLine("（已取消）");
                    return;
                }
                default -> {
                    // 忽略无关按键，保持菜单
                }
            }
        }
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

    /** 用某个会话的历史替换当前对话：清空上下文与界面，回显该会话全部消息。 */
    private void loadSession(Session session) {
        conversation.clear();
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
        }
        output.clear();
        output.resetScroll();
        output.append(BANNER);
        output.appendLine("输入 /help 查看命令，/quit 退出");
        output.appendLine("（已加载会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
        for (ChatMessage message : session.getMessages()) {
            conversation.addMessage(message);
            appendHistoryMessage(message);
        }
    }

    /** 把一条历史消息渲染进输出区：文本照常，工具块压缩为单行摘要。 */
    private void appendHistoryMessage(ChatMessage message) {
        String rendered = renderHistoryMessage(message);
        if (rendered.isEmpty()) {
            return;
        }
        if (message.role() == ChatMessage.Role.USER) {
            output.append("● " + rendered + "\n");
        } else {
            output.append(rendered);
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
        handleExchange(input, this::ctrlCPressed, () -> tui.repaint(output));
    }

    /** 测试用：注入输出面板（真实流程在 start() 中创建） */
    void setOutput(OutputPane output) {
        this.output = output;
    }

    /** 测试用：访问对话历史 */
    Conversation conversation() {
        return conversation;
    }

    /**
     * 单次输入触发 Agent 循环：追加 user 消息 → new Agent(...).run() 在虚拟线程跑 ReAct 循环 →
     * 主线程订阅事件队列逐条渲染（流式文本 / 工具卡片 / 轮次收尾 / 重试 / 错误 / 循环结束提示）。
     * ctrlC 注入中断源（真实终端为 Ctrl+C），repaint 注入重绘回调，便于用 FakeProvider 单测编排。
     */
    void handleExchange(String input, BooleanSupplier ctrlC, Runnable repaint) {
        output.resetScroll();
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, input));
        output.append("● " + input + "\n");
        repaint.run();

        Agent agent = new Agent(provider, conversation, toolRegistry,
                new ToolContext(Path.of(System.getProperty("user.dir"))), maxIterations());
        agent.setPlanMode(planMode);
        BlockingQueue<AgentEvent> events = agent.run();

        StreamPrinter printer = new StreamPrinter(output, repaint);
        List<ToolResult> turnResults = new ArrayList<>();
        while (true) {
            if (ctrlC.getAsBoolean()) {
                agent.cancel();
                output.appendLine("（已中断）");
                repaint.run();
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
                completeLoop(agent);
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
                turnResults = new ArrayList<>();
                printer = new StreamPrinter(output, repaint); // R3：跨轮不复用，收尾后新建
            } else if (event instanceof RetryEvent retry) {
                output.appendLine("（重试中：" + retry.reason() + "）");
                repaint.run();
            } else if (event instanceof ErrorEvent error) {
                output.appendLine("（错误：" + error.message() + "）");
                repaint.run();
            }
        }
        repaint.run();
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
    private void completeLoop(Agent agent) {
        switch (agent.termination()) {
            case MAX_ITERATIONS -> output.appendLine("（达到最大轮数，已停止执行）");
            case PLAN_DELIVERED -> {
                output.appendLine("（计划已交付）");
                Path plan = agent.planPath();
                if (plan != null) {
                    try {
                        output.append(Files.readString(plan));
                    } catch (IOException e) {
                        log.warn("读取计划文件失败：{}", e.getMessage());
                    }
                }
                output.appendLine("输入 /do 退出 plan 模式开始执行");
            }
            case CANCELED -> output.appendLine("（已中断）");
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
