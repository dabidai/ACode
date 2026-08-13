package com.acode;

import com.acode.config.AppConfig;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
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
import com.acode.tool.ToolExecutor;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * T12 主循环与装配：配置 → Provider → 会话 → TUI 串成完整对话。
 * Provider 在后台 daemon 线程流式生成，主线程负责重绘与 Ctrl+C 中断检测；
 * 退出时把完整消息历史存为独立会话文件。
 */
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private static final int MAX_TOKENS = 8192;

    /** 工具结果入历史前的截断上限（字符），避免超长输出撑爆上下文窗口 */
    private static final int MAX_TOOL_RESULT_HISTORY_CHARS = 2000;

    private static final String BANNER = """
             ___   ____    ___   ___   ____
            / _ \\ / ___|  / _ \\ / _ \\ |  _ \\
           | | | | |     | | | | | | || | | |
           | |_| | |___  | |_| | |_| || |_| |
            \\___/ \\____|  \\___/ \\___/ |____/
                          ACode v0.1.0
            """;

    private final ChatProvider provider;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;
    private final boolean resume;

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

    /** 包可见：测试可注入假 Provider 驱动两轮闭环 */
    ConversationController(ChatProvider provider, AppConfig config, boolean resume) {
        this.provider = provider;
        boolean thinking = "anthropic".equals(config.getProtocol());
        this.conversation = new Conversation(config.getModel(), thinking, MAX_TOKENS,
                config.getMaxContextTokens());
        this.toolRegistry = new ToolRegistry();
        DefaultToolset.registerAll(toolRegistry);
        conversation.setTools(toolRegistry.availableList());
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

    /** 超长工具结果入历史前截断，避免撑爆上下文窗口。 */
    private static String truncateForHistory(String text) {
        if (text == null || text.length() <= MAX_TOOL_RESULT_HISTORY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_RESULT_HISTORY_CHARS) + "\n…（结果过长，已截断）";
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
     * 单次输入的两轮闭环（ch03）：第一轮带工具请求，模型发起 tool_use → 展示卡片、执行、
     * 回传 tool_result 后第二轮请求出最终文本；第二轮仍 tool_use → 只显示文本并提示
     * 「连环调用未支持」。ctrlC 注入中断源（真实终端为 Ctrl+C），repaint 注入重绘回调，
     * 便于用 FakeProvider 单测两轮编排。
     */
    void handleExchange(String input, BooleanSupplier ctrlC, Runnable repaint) {
        output.resetScroll();
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, input));
        output.append("● " + input + "\n");
        repaint.run();

        // ---------- 第一轮：带工具流式请求 ----------
        RoundResult round1 = streamRound(conversation.buildRequest(), ctrlC, repaint);
        if (round1.interrupted() || round1.hasError()) {
            return;
        }

        if (round1.toolUses().isEmpty()) {
            if (!round1.text().isEmpty()) {
                conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, round1.text()));
            }
            return;
        }

        // 第一轮产生了工具调用：assistant 消息 = 文本 + tool_use 块
        List<ContentBlock> assistantBlocks = new ArrayList<>();
        if (!round1.text().isEmpty()) {
            assistantBlocks.add(new TextBlock(round1.text()));
        }
        assistantBlocks.addAll(round1.toolUses());
        conversation.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, assistantBlocks));

        // 执行工具（可被 Ctrl+C 中断）
        ToolRunOutcome outcome = executeTools(round1.toolUses(), ctrlC);
        round1.printer().updateToolCalls(outcome.results());
        repaint.run();
        if (outcome.interrupted()) {
            output.appendLine("（已中断工具执行，跳过后续回复）");
            repaint.run();
            return;
        }

        // 回传 tool_result（成功/失败结果都回传，超长结果入历史前截断）
        List<ToolResultBlock> resultBlocks = new ArrayList<>();
        for (int i = 0; i < round1.toolUses().size(); i++) {
            ToolResult result = outcome.results().get(i);
            resultBlocks.add(new ToolResultBlock(
                    round1.toolUses().get(i).id(), truncateForHistory(result.content()), result.isError()));
        }
        conversation.addToolResults(resultBlocks);

        // ---------- 第二轮：最终文本 ----------
        RoundResult round2 = streamRound(conversation.buildRequest(), ctrlC, repaint);
        if (round2.interrupted() || round2.hasError()) {
            return;
        }
        if (!round2.toolUses().isEmpty()) {
            output.appendLine("（连环工具调用暂不支持，仅显示以上文本）");
            repaint.run();
        }
        if (!round2.text().isEmpty()) {
            conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, round2.text()));
        }
    }

    /**
     * 流式请求一轮：后台线程驱动 provider，主线程轮询重绘与 Ctrl+C。
     * 收集文本增量与 tool_use 块；中断/出错时返回相应标记。
     */
    private RoundResult streamRound(ChatRequest request, BooleanSupplier ctrlC, Runnable repaint) {
        AtomicBoolean repaintRequested = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<ProviderException> error = new AtomicReference<>();
        StreamPrinter printer = new StreamPrinter(output, () -> repaintRequested.set(true));
        StringBuilder reply = new StringBuilder();
        List<ToolUseBlock> toolUses = new ArrayList<>();

        ChatListener listener = new ChatListener() {
            @Override
            public void onDelta(String delta) {
                if (interrupted.get()) {
                    return;
                }
                reply.append(delta);
                printer.onDelta(delta);
            }

            @Override
            public void onToolUse(ToolUseBlock toolUse) {
                if (interrupted.get()) {
                    return;
                }
                toolUses.add(toolUse);
                printer.onToolUse(toolUse);
            }

            @Override
            public void onComplete() {
                if (interrupted.get()) {
                    return;
                }
                completed.set(true);
                printer.onComplete();
            }

            @Override
            public void onError(ProviderException e) {
                if (interrupted.get()) {
                    return;
                }
                error.set(e);
                printer.onError(e);
            }
        };

        Thread worker = new Thread(() -> {
            try {
                provider.streamChat(request, listener);
            } catch (RuntimeException e) {
                if (!interrupted.get()) {
                    error.set(new ProviderException("生成过程异常：" + e.getMessage(), e));
                    printer.onError(error.get());
                }
            }
        }, "acode-provider");
        worker.setDaemon(true);
        worker.start();

        while (worker.isAlive() && !interrupted.get()) {
            if (repaintRequested.getAndSet(false)) {
                repaint.run();
            }
            if (ctrlC.getAsBoolean()) {
                interrupted.set(true);
                worker.interrupt();
                output.appendLine("（已中断）");
                repaint.run();
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!interrupted.get()) {
            repaint.run();
        }
        return new RoundResult(reply.toString(), toolUses, printer,
                interrupted.get(), error.get() != null || !completed.get());
    }

    /**
     * 顺序执行一批工具调用：后台线程执行、主线程轮询 Ctrl+C 以便中断。
     * 被中断时未执行的调用结果标记为「已取消」。
     */
    private ToolRunOutcome executeTools(List<ToolUseBlock> calls, BooleanSupplier ctrlC) {
        ToolExecutor executor = new ToolExecutor(toolRegistry,
                new ToolContext(Path.of(System.getProperty("user.dir"))));
        List<ToolResult> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            results.add(null);
        }
        AtomicBoolean interruptRequested = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            for (int i = 0; i < calls.size(); i++) {
                if (interruptRequested.get()) {
                    break;
                }
                results.set(i, executor.execute(calls.get(i)));
            }
        }, "acode-tools");
        worker.setDaemon(true);
        worker.start();

        while (worker.isAlive()) {
            if (ctrlC.getAsBoolean()) {
                interruptRequested.set(true);
                worker.interrupt();
                break;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean interrupted = interruptRequested.get();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, ToolResult.failure("已取消"));
            }
        }
        return new ToolRunOutcome(results, interrupted);
    }

    /** 一轮流式请求的收集结果 */
    private record RoundResult(String text, List<ToolUseBlock> toolUses, StreamPrinter printer,
                               boolean interrupted, boolean hasError) {
    }

    /** 一批工具执行结果：结果列表 + 是否被用户中断 */
    private record ToolRunOutcome(List<ToolResult> results, boolean interrupted) {
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
