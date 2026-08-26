package com.acode;

import com.acode.conversation.Conversation;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.session.SessionManager;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.CommandRouter;
import com.acode.ui.InputPane;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.io.Writer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 主循环命令分发：读输入 → 路由 → 执行各命令（/clear /help /resume /plan /do /permission-mode /chat）。 */
public class CommandProcessor {

    private final AcodeTerminal tui;
    private final OutputPane output;
    private final RenderContext renderContext;
    private final Conversation conversation;
    private final SessionManager sessionManager;
    private final Supplier<PermissionChecker> checkerSupplier;
    private final Consumer<String> chatHandler;
    private final Consumer<Boolean> planModeSetter;

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

    public void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), "> ");
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        while (true) {
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
                sessionManager.saveSession();
                return;
            }
            switch (CommandRouter.route(line)) {
                case QUIT -> {
                    sessionManager.saveSession();
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
                case RESUME -> sessionManager.selectSession();
                case PLAN -> {
                    planModeSetter.accept(true);
                    output.appendLine("（已进入规划模式：只读探索，计划落盘到 .acode/plans/）");
                    live.appendCommitted(writer, "（已进入规划模式：只读探索，计划落盘到 .acode/plans/）");
                }
                case DO -> {
                    planModeSetter.accept(false);
                    output.appendLine("（已退出规划模式，开始执行）");
                    live.appendCommitted(writer, "（已退出规划模式，开始执行）");
                }
                case PERMISSION_MODE -> handlePermissionMode(line.trim().substring("/permission-mode".length()).trim(), live, writer);
                case SKIP -> {
                    // 空白输入，忽略
                }
                case CHAT -> chatHandler.accept(line);
            }
        }
    }

    /**
     * /permission-mode 切档：无参数输出当前模式；参数须为 4 合法值之一（大小写敏感、无多余参数）。
     * 非法值输出错误、模式不变；合法切档只改内存 volatile mode，不写回 config.yaml。
     */
    void handlePermissionMode(String arg, LiveRegionRenderer live, Writer writer) {
        String modeArg = arg == null ? "" : arg.trim();
        if (modeArg.isEmpty()) {
            String line = "当前权限模式：" + currentPermissionModeName();
            output.appendLine(line);
            live.appendCommitted(writer, line);
            return;
        }
        PermissionMode mode = PermissionMode.fromConfig(modeArg);
        if (mode == null) {
            String line = "（非法权限模式：" + modeArg + "，可选：default/acceptEdits/plan/bypassPermissions）";
            output.appendLine(line);
            live.appendCommitted(writer, line);
            return;
        }
        PermissionChecker checker = checkerSupplier.get();
        checker.setMode(mode);
        String line = "（已切换到权限模式：" + mode.configValue() + "）";
        output.appendLine(line);
        live.appendCommitted(writer, line);
    }

    private String currentPermissionModeName() {
        return checkerSupplier.get().mode().configValue();
    }
}
