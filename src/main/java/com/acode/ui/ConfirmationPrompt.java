package com.acode.ui;

import com.acode.permission.PermissionResponse;
import java.io.Writer;
import java.util.List;

/**
 * 工具确认提示：渲染「要执行 X …？」为提交行，↑↓ 选择「放行/始终允许/拒绝」、Enter 确认、Esc/EOF 取消（=拒绝）。
 * 每次应答都以提交行写回滚，提示与结果留在历史、无活跃区残影。菜单按键源抽象为 MenuKeySource，便于测试注入。
 */
public class ConfirmationPrompt {

    private final MenuKeySource keys;
    private final LiveRegionRenderer live;
    private final Writer writer;

    public ConfirmationPrompt(MenuKeySource keys, LiveRegionRenderer live, Writer writer) {
        this.keys = keys;
        this.live = live;
        this.writer = writer;
    }

    /** 渲染确认提示并弹三选一菜单；Esc/Ctrl+C/EOF 视为取消=拒绝。 */
    public PermissionResponse ask(String toolName, String argsSummary) {
        live.appendCommitted(writer, promptLine(toolName, argsSummary));
        live.commitRegion();
        int selected = new SelectionMenu(List.of("放行", "始终允许", "拒绝"), null, 0).select(live, writer, keys);
        if (selected == 0) {
            live.appendCommitted(writer, "（已批准执行「" + toolName + "」）");
            return PermissionResponse.ALLOW;
        }
        if (selected == 1) {
            live.appendCommitted(writer, "（已记录「始终允许」：「" + toolName + "」）");
            return PermissionResponse.ALLOW_ALWAYS;
        }
        if (selected == 2) {
            live.appendCommitted(writer, "（已拒绝执行「" + toolName + "」）");
            return PermissionResponse.DENY;
        }
        live.appendCommitted(writer, "（已取消）");
        return PermissionResponse.DENY;
    }

    /** 提示行：工具名 + 参数摘要 + 问句；摘要为空时不带括号。 */
    static String promptLine(String toolName, String argsSummary) {
        String summary = argsSummary == null ? "" : argsSummary.trim();
        return summary.isEmpty()
                ? "要执行「" + toolName + "」？"
                : "要执行「" + toolName + "（" + summary + "）」？";
    }
}
