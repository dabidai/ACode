package com.acode.command;

import com.acode.memory.MemoryStore;
import com.acode.memory.MemoryType;
import com.acode.ui.UIController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内置命令集中定义处：每条命令一个声明方法，注册清单在 {@link #registerAll} 里按展示顺序集中排列。
 * 帮助命令经闭包捕获注册中心列出可见命令（注册中心不进命令上下文）。
 * 后续任务的命令（压缩/恢复/记忆/权限、清空/规划/执行、审查）都追加进本类。
 */
public final class BuiltinCommands {

    /** /status 标题下的分隔线，与验收清单逐字一致 */
    private static final String DIVIDER = "─".repeat(13);
    /** /status 四类记忆的展示顺序（固定） */
    private static final List<MemoryType> MEMORY_ORDER = List.of(
            MemoryType.USER, MemoryType.FEEDBACK, MemoryType.PROJECT, MemoryType.REFERENCE);

    private BuiltinCommands() {
    }

    /** 一次性注册全部内置命令；注册顺序即帮助与补全的展示顺序 */
    public static void registerAll(CommandRegistry registry) {
        registry.register(help(registry));
        registry.register(status());
        registry.register(quit());
    }

    /** /help：无参数按类型分三段（本地 / 本地界面 / 提示词）列出可见命令；带参数输出指定命令的详情 */
    static Command help(CommandRegistry registry) {
        return new Command("help", List.of("h", "?"), "显示帮助信息", "/help",
                CommandType.LOCAL, "<命令名>", false, ctx -> {
            if (ctx.args() == null) {
                emit(ctx.ui(), helpListLines(registry));
            } else {
                Command target = registry.find(ctx.args().trim());
                if (target == null) {
                    emit(ctx.ui(), List.of("未找到命令：/" + ctx.args().trim()
                            + "（输入 /help 查看可用命令）"));
                } else {
                    emit(ctx.ui(), detailLines(target));
                }
            }
            return CommandResult.CONTINUE;
        });
    }

    /** /status：一屏聚合权限模式、上下文占用、已启用工具、四类记忆条数、工作目录、版本 */
    static Command status() {
        return new Command("status", List.of("s"), "显示状态信息", "/status",
                CommandType.LOCAL, null, false, ctx -> {
            emit(ctx.ui(), statusLines(ctx));
            return CommandResult.CONTINUE;
        });
    }

    /** /quit：静默退出主循环（无输出、不接受参数、无别名） */
    static Command quit() {
        return new Command("quit", List.of(), "退出程序", "/quit",
                CommandType.LOCAL, null, false, ctx -> CommandResult.EXIT);
    }

    // ---- /help 渲染 ----

    private static List<String> helpListLines(CommandRegistry registry) {
        List<Command> visible = registry.visible();
        int nameWidth = 0;
        for (Command command : visible) {
            nameWidth = Math.max(nameWidth, nameColumn(command).length());
        }
        List<String> lines = new ArrayList<>();
        lines.add("可用命令：");
        boolean firstSection = true;
        for (CommandType type : CommandType.values()) {
            List<Command> section = visible.stream().filter(c -> c.type() == type).toList();
            if (section.isEmpty()) {
                continue;
            }
            if (!firstSection) {
                lines.add("");
            }
            firstSection = false;
            for (Command command : section) {
                String column = nameColumn(command);
                lines.add("  " + column
                        + " ".repeat(nameWidth - column.length() + 4) + command.description());
            }
        }
        lines.add("");
        lines.add("输入 /help <命令名> 查看详细用法。");
        return lines;
    }

    /** 名称列：/name 后跟 /alias（逗号加空格分隔） */
    private static String nameColumn(Command command) {
        StringBuilder sb = new StringBuilder("/").append(command.name());
        for (String alias : command.aliases()) {
            sb.append(", /").append(alias);
        }
        return sb.toString();
    }

    private static List<String> detailLines(Command command) {
        List<String> lines = new ArrayList<>();
        lines.add("描述：" + command.description());
        lines.add("用法：" + command.usage());
        if (command.argumentHint() != null) {
            lines.add("参数：" + command.argumentHint());
        }
        if (!command.aliases().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String alias : command.aliases()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("/").append(alias);
            }
            lines.add("别名：" + sb);
        }
        return lines;
    }

    // ---- /status 渲染 ----

    private static List<String> statusLines(CommandContext ctx) {
        UIController.ContextUsage usage = ctx.ui().contextUsage();
        int percent = (int) Math.round(usage.used() * 100.0 / usage.max());
        List<String> lines = new ArrayList<>();
        lines.add("ACode 状态");
        lines.add(DIVIDER);
        lines.add("模式：" + ctx.permissionChecker().mode().configValue());
        lines.add("Token：" + grouped(usage.used()) + " / " + grouped(usage.max())
                + "（" + percent + "%）");
        lines.add("工具：" + ctx.toolRegistry().availableList().size() + " 个已启用");
        lines.add("记忆：" + memorySummary(ctx.memoryManager().store()));
        lines.add("工作目录：" + ctx.workingDirectory());
        lines.add("版本：" + ctx.version());
        return lines;
    }

    private static String grouped(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 四类记忆条数（两级合并后按类型归并，顺序固定 user / feedback / project / reference） */
    private static String memorySummary(MemoryStore store) {
        StringBuilder sb = new StringBuilder();
        for (MemoryType type : MEMORY_ORDER) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(type.slug()).append(' ')
                    .append(store.listAll().stream().filter(m -> m.type() == type).count())
                    .append(" 条");
        }
        return sb.toString();
    }

    private static void emit(UIController ui, List<String> lines) {
        for (String line : lines) {
            ui.appendSystemMessage(line);
        }
    }
}
