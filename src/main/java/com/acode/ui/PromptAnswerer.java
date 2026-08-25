package com.acode.ui;

import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.permission.PermissionResponse;

import java.io.Writer;

/** 确认/选择交互应答：无终端（纯测试环境）时确认拒绝、选择取消。 */
public final class PromptAnswerer {

    private final AcodeTerminal tui;
    private final RenderContext renderContext;

    public PromptAnswerer(AcodeTerminal tui, RenderContext renderContext) {
        this.tui = tui;
        this.renderContext = renderContext;
    }

    /** 默认确认应答：渲染「要执行 X …？」并弹三选一菜单；无终端（纯测试环境）视为拒绝。 */
    public PermissionResponse answerConfirmationPrompt(ConfirmationRequestEvent event) {
        if (tui == null) {
            return PermissionResponse.DENY;
        }
        ConfirmationPrompt prompt = new ConfirmationPrompt(
                new TerminalMenuKeySource(tui.terminal().reader()),
                renderContext.liveRenderer(), renderContext.screenWriter());
        return prompt.ask(event.toolName(), event.argsSummary());
    }

    /** 默认选择应答：渲染 question 并弹多选项菜单；无终端（纯测试环境）返回 null（取消）。 */
    public String answerChoicePrompt(ChoiceRequestEvent event) {
        if (tui == null) {
            return null;
        }
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();
        live.appendCommitted(writer, event.question());
        live.commitRegion();
        int selected = new SelectionMenu(event.options(), "（↑/↓ 选择，回车确认，Esc 取消）", 0)
                .select(live, writer, new TerminalMenuKeySource(tui.terminal().reader()));
        if (selected < 0) {
            live.appendCommitted(writer, "（已取消）");
            return null;
        }
        String picked = event.options().get(selected);
        live.appendCommitted(writer, "（已选择「" + picked + "」）");
        return picked;
    }
}
