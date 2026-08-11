package com.acode;

import com.acode.config.AppConfig;
import com.acode.config.ConfigException;
import com.acode.config.ConfigLoader;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.ProviderException;
import com.acode.provider.anthropic.AnthropicProvider;
import com.acode.provider.openai.OpenAiProvider;
import com.acode.session.Session;
import com.acode.session.SessionStore;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.CommandRouter;
import com.acode.ui.InputPane;
import com.acode.ui.OutputPane;
import com.acode.ui.StreamPrinter;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Conversation conversation;
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
        this.provider = buildProvider(config);
        boolean thinking = "anthropic".equals(config.getProtocol());
        this.conversation = new Conversation(config.getModel(), thinking, MAX_TOKENS,
                config.getMaxContextTokens());
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
            if (message.role() == ChatMessage.Role.USER) {
                output.appendLine("● " + message.content());
            } else {
                output.append(message.content());
            }
        }
        output.appendLine("（已恢复会话 " + session.getId() + "，共 " + session.getMessages().size() + " 条消息）");
    }

    private void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), "> ",
                delta -> {
                    output.scrollBy(delta);
                    tui.repaintOutputArea(output);
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
            if (message.role() == ChatMessage.Role.USER) {
                output.appendLine("● " + message.content());
            } else {
                output.append(message.content());
            }
        }
    }

    private void handleChat(String input) {
        output.resetScroll();
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, input));
        output.append("● " + input + "\n");
        tui.repaint(output);

        ChatRequest request = conversation.buildRequest();
        AtomicBoolean repaintRequested = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean success = new AtomicBoolean(false);
        StreamPrinter printer = new StreamPrinter(output, () -> repaintRequested.set(true));

        StringBuilder reply = new StringBuilder();
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
            public void onComplete() {
                if (interrupted.get()) {
                    return;
                }
                success.set(true);
                printer.onComplete();
            }

            @Override
            public void onError(ProviderException error) {
                if (interrupted.get()) {
                    return;
                }
                printer.onError(error);
            }
        };

        Thread worker = new Thread(() -> {
            try {
                provider.streamChat(request, listener);
            } catch (RuntimeException e) {
                if (!interrupted.get()) {
                    listener.onError(new ProviderException("生成过程异常：" + e.getMessage(), e));
                }
            }
        }, "acode-provider");
        worker.setDaemon(true);
        worker.start();

        while (worker.isAlive() && !interrupted.get()) {
            if (repaintRequested.getAndSet(false)) {
                tui.repaint(output);
            }
            if (ctrlCPressed()) {
                interrupted.set(true);
                worker.interrupt();
                output.appendLine("（已中断）");
                tui.repaint(output);
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
            tui.repaint(output);
        }
        if (success.get()) {
            conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, reply.toString()));
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
