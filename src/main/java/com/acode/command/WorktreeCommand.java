package com.acode.command;

import com.acode.worktree.WorktreeManager;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class WorktreeCommand {
    private WorktreeCommand() {}
    private static final String USAGE = "用法：/worktree（列出全部）｜ /worktree create <名称> ｜ enter <名称> ｜ exit ｜ remove <名称> [--force] ｜ prune";
    public static Command command(Supplier<WorktreeManager> supplier) {
        return new Command("worktree", List.of(), "管理隔离工作目录", "/worktree create <名称>",
                CommandType.LOCAL, "create/enter/exit/remove/prune", false, ctx -> {
            WorktreeManager manager = supplier.get();
            if (manager == null) { ctx.ui().appendSystemMessage("Worktree 管理器未装配"); return CommandResult.CONTINUE; }
            try {
                String[] args = ctx.args() == null || ctx.args().isBlank() ? new String[0] : ctx.args().strip().split("\\s+");
                if (args.length == 0) {
                    var rows = manager.list();
                    if (rows.isEmpty()) ctx.ui().appendSystemMessage("（没有 Worktree，输入 /worktree create <名称> 创建）");
                    else for (var row : rows) ctx.ui().appendSystemMessage((row.current() ? "→ " : "  ") + row.entry().name()
                            + "  分支 " + row.entry().branch() + "  " + row.status() + "  " + row.entry().path().replace('\\', '/'));
                } else {
                    String verb = args[0].toLowerCase(Locale.ROOT);
                    if (verb.equals("create") && args.length == 2) {
                        var created = manager.create(args[1]); manager.enter(args[1]);
                        ctx.ui().appendSystemMessage(created.recovered() ? "已恢复并进入 Worktree：" + args[1] + "（复用已有目录）"
                                : "已创建并进入 Worktree：" + args[1] + "（分支 " + created.entry().branch() + "）");
                    } else if (verb.equals("enter") && args.length == 2) {
                        manager.enter(args[1]); ctx.ui().appendSystemMessage("已进入 Worktree：" + args[1]);
                    } else if (verb.equals("exit") && args.length == 1) {
                        ctx.ui().appendSystemMessage("已退出 Worktree：" + manager.exit() + "，回到主目录");
                    } else if (verb.equals("remove") && (args.length == 2 || args.length == 3 && args[2].equals("--force"))) {
                        manager.remove(args[1], args.length == 3); ctx.ui().appendSystemMessage("已删除 Worktree：" + args[1]);
                    } else if (verb.equals("prune") && args.length == 1) {
                        int count = manager.prune(); ctx.ui().appendSystemMessage(count == 0 ? "没有需要清理的 Worktree" : "已清理 " + count + " 个过期 Worktree");
                    } else ctx.ui().appendSystemMessage(USAGE);
                }
            } catch (IOException | IllegalArgumentException e) { ctx.ui().appendSystemMessage(e.getMessage()); }
            finally { manager.drainWarnings().forEach(ctx.ui()::appendSystemMessage); }
            return CommandResult.CONTINUE;
        });
    }
}
