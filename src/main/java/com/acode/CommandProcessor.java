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
import com.acode.ui.SelectionMenu;
import com.acode.ui.TerminalMenuKeySource;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.io.Writer;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 主循环命令分发：读输入 → 路由 → 执行各命令（/clear /help /resume /plan /do /permission-mode /model /chat）。 */
public class CommandProcessor {

    private final AcodeTerminal tui;
    private final OutputPane output;
    private final RenderContext renderContext;
    private final Conversation conversation;
    private final SessionManager sessionManager;
    private final Supplier<PermissionChecker> checkerSupplier;
    private final Consumer<String> chatHandler;
    private final Consumer<Boolean> planModeSetter;
    private final Supplier<List<String>> modelOptionsSupplier;
    private final Consumer<String> modelSetter;

    public CommandProcessor(AcodeTerminal tui, OutputPane output, RenderContext renderContext,
                            Conversation conversation, SessionManager sessionManager,
                            Supplier<PermissionChecker> checkerSupplier,
                            Consumer<String> chatHandler, Consumer<Boolean> planModeSetter,
                            Supplier<List<String>> modelOptionsSupplier,
                            Consumer<String> modelSetter) {
        this.tui = tui;
        this.output = output;
        this.renderContext = renderContext;
        this.conversation = conversation;
        this.sessionManager = sessionManager;
        this.checkerSupplier = checkerSupplier;
        this.chatHandler = chatHandler;
        this.planModeSetter = planModeSetter;
        this.modelOptionsSupplier = modelOptionsSupplier;
        this.modelSetter = modelSetter;
    }

    public void mainLoop() {
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        InputPane input = new InputPane(tui.terminal(), "> ");
        input.setCyclePermissionCallback(() -> cyclePermissionMode(live, writer));
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
                    live.clearScreen(writer);
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
                case MODEL -> handleModel(line.trim().substring("/model".length()).trim(), live, writer);
                case SKIP -> {
                    // 空白输入，忽略
                }
                case CHAT -> chatHandler.accept(line);
            }
        }
    }

    /**
     * /permission-mode 切档：无参数弹交互菜单（tui 为 null 时 fallback 文本）；
     * 参数须为 4 合法值之一（大小写敏感、无多余参数）。
     * 非法值输出错误、模式不变；合法切档只改内存 volatile mode，不写回 config.yaml。
     */
    void handlePermissionMode(String arg, LiveRegionRenderer live, Writer writer) {
        String modeArg = arg == null ? "" : arg.trim();
        if (modeArg.isEmpty()) {
            if (tui == null) {
                String line = "当前权限模式：" + currentPermissionModeName();
                output.appendLine(line);
                live.appendCommitted(writer, line);
                return;
            }
            showPermissionModeMenu(live, writer);
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

    private void showPermissionModeMenu(LiveRegionRenderer live, Writer writer) {
        PermissionMode current = checkerSupplier.get().mode();
        List<String> options = List.of(
                "default            读放行，写/执行需确认",
                "acceptEdits        读/写放行，执行需确认",
                "plan               同 default（配合 /plan /do 工作流）",
                "bypassPermissions  全部放行（危险命令黑名单仍生效）");
        PermissionMode[] modes = {PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN, PermissionMode.BYPASS};
        int initialSelected = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) {
                initialSelected = i;
                break;
            }
        }
        int selected = new SelectionMenu(options, "（↑/↓ 选择权限模式，回车确认，Esc 取消）", initialSelected)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
        if (selected >= 0) {
            PermissionMode chosen = modes[selected];
            checkerSupplier.get().setMode(chosen);
            String line = "（已切换到权限模式：" + chosen.configValue() + "）";
            output.appendLine(line);
            live.appendCommitted(writer, line);
        } else {
            output.appendLine("（已取消）");
            live.appendCommitted(writer, "（已取消）");
        }
    }

    private String currentPermissionModeName() {
        return checkerSupplier.get().mode().configValue();
    }

    /** Shift+Tab 快捷切换：循环 default → acceptEdits → plan → bypassPermissions → default */
    private void cyclePermissionMode(LiveRegionRenderer live, Writer writer) {
        PermissionMode[] cycle = {PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN, PermissionMode.BYPASS};
        PermissionMode current = checkerSupplier.get().mode();
        int idx = 0;
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] == current) {
                idx = i;
                break;
            }
        }
        PermissionMode next = cycle[(idx + 1) % cycle.length];
        checkerSupplier.get().setMode(next);
        String line = "（权限模式已切换：" + next.configValue() + "）";
        output.appendLine(line);
        live.appendCommitted(writer, line);
    }

    /**
     * /model 切换模型：无参数时弹出选择菜单（有 CC Switch 映射时）或显示当前模型；
     * 有参数时直接切换到指定模型。
     */
    void handleModel(String arg, LiveRegionRenderer live, Writer writer) {
        String modelArg = arg == null ? "" : arg.trim();
        if (!modelArg.isEmpty()) {
            modelSetter.accept(modelArg);
            String line = "（已切换模型：" + modelArg + "）";
            output.appendLine(line);
            live.appendCommitted(writer, line);
            return;
        }

        List<String> options = modelOptionsSupplier.get();
        if (options.isEmpty()) {
            String line = "当前模型：" + conversation.getModel();
            output.appendLine(line);
            live.appendCommitted(writer, line);
            return;
        }

        String currentModel = conversation.getModel();
        int initialSelected = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).contains(currentModel)) {
                initialSelected = i;
                break;
            }
        }

        int selected = new SelectionMenu(options, "（↑/↓ 选择模型，回车确认，Esc 取消）", initialSelected)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
        if (selected >= 0) {
            String chosen = options.get(selected);
            String actualModel = extractActualModel(chosen);
            modelSetter.accept(actualModel);
            String line = "（已切换模型：" + actualModel + "）";
            output.appendLine(line);
            live.appendCommitted(writer, line);
        } else {
            output.appendLine("（已取消）");
            live.appendCommitted(writer, "（已取消）");
        }
    }

    private static String extractActualModel(String displayEntry) {
        int arrowIdx = displayEntry.indexOf("→");
        if (arrowIdx < 0) {
            return displayEntry.trim();
        }
        return displayEntry.substring(arrowIdx + 1).trim();
    }
}
