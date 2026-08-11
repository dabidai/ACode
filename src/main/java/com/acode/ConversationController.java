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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
        InputPane input = new InputPane(tui.terminal(), "> ");
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
                    conversation.clear();
                    output.appendLine("（已清空）");
                }
                case HELP -> output.append(CommandRouter.HELP_TEXT);
                case SKIP -> {
                    // 空白输入，仅重绘
                }
                case CHAT -> handleChat(line);
            }
        }
    }

    private void handleChat(String input) {
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
