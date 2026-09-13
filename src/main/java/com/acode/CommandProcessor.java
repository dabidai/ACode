package com.acode;

import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionChecker;
import com.acode.session.SessionManager;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.InputPane;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 主循环：读一行 → 交给命令调度器 → 按返回结果决定去留；两种退出路径都关闭会话。 */
public class CommandProcessor {

    private final AcodeTerminal tui;
    private final OutputPane output;
    private final RenderContext renderContext;
    private final Conversation conversation;
    private final SessionManager sessionManager;
    private final Supplier<PermissionChecker> checkerSupplier;
    private final Consumer<String> chatHandler;
    private final Consumer<Boolean> planModeSetter;

    /** 命令调度器：由装配方注入（T12），注入前主循环不可用 */
    private CommandDispatcher commandDispatcher;

    public CommandProcessor(AcodeTerminal tui, OutputPane output, RenderContext renderContext,
                            Conversation conversation, SessionManager sessionManager,
                            Supplier<PermissionChecker> checkerSupplier,
                            Consumer<String> chatHandler, Consumer<Boolean> planModeSetter) {
        this.tui = tui;
        this.output = output;
        this.renderContext = renderContext;
        this.conversation = conversation;
        this.sessionManager = sessionManager;
        this.checkerSupplier = checkerSupplier;
        this.chatHandler = chatHandler;
        this.planModeSetter = planModeSetter;
    }

    /** 注入命令调度器：主循环的每一行输入都交给它执行 */
    public void setCommandDispatcher(CommandDispatcher dispatcher) {
        if (dispatcher != null) {
            this.commandDispatcher = dispatcher;
        }
    }

    public void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), "> ", new CommandRegistry());
        while (true) {
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
                sessionManager.closeSession();
                return;
            }
            if (handleLine(line) == CommandResult.EXIT) {
                sessionManager.closeSession();
                return;
            }
        }
    }

    /** 单行输入：原样交给调度器，返回值即主循环去留 */
    CommandResult handleLine(String line) {
        return Objects.requireNonNull(commandDispatcher, "命令调度器未注入").dispatch(line);
    }
}
