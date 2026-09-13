package com.acode.command;

import com.acode.context.CompactExecutor;
import com.acode.memory.MemoryExtractor;
import com.acode.memory.MemoryFile;
import com.acode.memory.MemoryStore;
import com.acode.memory.MemoryType;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionRule;
import com.acode.permission.RuleEngine;
import com.acode.prompt.ProjectInstructions;
import com.acode.provider.ChatMessage;
import com.acode.session.Session;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** /compact 手动压缩的固定阈值（绝对 token 量；与自动压缩的比例触发点是两套口径） */
    private static final int COMPACT_THRESHOLD = 5_000;
    /** /memory 长期记忆列举的类别顺序：项目级在前（project/reference）、用户级在后（user/feedback） */
    private static final List<MemoryType> MEMORY_VIEW_ORDER = List.of(
            MemoryType.PROJECT, MemoryType.REFERENCE, MemoryType.USER, MemoryType.FEEDBACK);

    private BuiltinCommands() {
    }

    /** 一次性注册全部内置命令；注册顺序即帮助与补全的展示顺序 */
    public static void registerAll(CommandRegistry registry) {
        registry.register(help(registry));
        registry.register(compact());
        registry.register(resume());
        registry.register(memory());
        registry.register(permission());
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

    /** /compact：占用低于阈值直接提示无需压缩；否则走既有手动压缩路径（三段式输出 + 失败兜底），带参数时作为保留重点 */
    static Command compact() {
        return new Command("compact", List.of("c"), "压缩上下文", "/compact",
                CommandType.LOCAL, "<需要保留的重点>", false, ctx -> {
            UIController.ContextUsage usage = ctx.ui().contextUsage();
            if (usage.used() < COMPACT_THRESHOLD) {
                emit(ctx.ui(), List.of("（当前上下文约 " + usage.used() + " token，无需压缩）"));
                return CommandResult.CONTINUE;
            }
            emit(ctx.ui(), List.of("正在压缩…"));
            try {
                CompactExecutor.Result result = ctx.contextManager().executor().run(true, ctx.args());
                String line;
                if (result.noChange()) {
                    line = "（没有需要压缩的内容）";
                } else if (result.failed()) {
                    line = "压缩失败：" + result.reason();
                } else {
                    line = "压缩完成：压缩前约 " + result.beforeEstimate()
                            + " token → 压缩后约 " + result.afterEstimate() + " token";
                }
                emit(ctx.ui(), List.of(line));
            } catch (RuntimeException e) {
                emit(ctx.ui(), List.of("压缩失败：" + e.getMessage()));
            }
            return CommandResult.CONTINUE;
        });
    }

    /** /resume：弹出会话选择菜单（沿用既有会话列表与加载入口）；不接受任何参数 */
    static Command resume() {
        return new Command("resume", List.of(), "恢复历史会话", "/resume",
                CommandType.LOCAL, null, false, ctx -> {
            if (ctx.args() != null) {
                emit(ctx.ui(), List.of("用法：/resume（弹出会话选择菜单，不接受参数）"));
                return CommandResult.CONTINUE;
            }
            List<Session> sessions = ctx.sessionManager().store().list();
            if (sessions.isEmpty()) {
                emit(ctx.ui(), List.of("（没有可恢复的会话）"));
                return CommandResult.CONTINUE;
            }
            List<MenuEntry> entries = new ArrayList<>(sessions.size());
            for (Session session : sessions) {
                entries.add(MenuEntry.item(sessionLabel(session)));
            }
            int selected = ctx.ui().selectMenu(entries, "（↑/↓ 选择会话，回车加载，Esc 取消）");
            if (selected >= 0) {
                ctx.sessionManager().open(sessions.get(selected));
            } else {
                emit(ctx.ui(), List.of("（已取消）"));
            }
            return CommandResult.CONTINUE;
        });
    }

    /** /memory：无参数弹三层指令文件菜单；run 为全章唯一保留词（大小写不敏感），立即提取一次长期记忆 */
    static Command memory() {
        return new Command("memory", List.of(), "查看/创建指令文件；run 提取记忆", "/memory",
                CommandType.LOCAL, "run（立即提取长期记忆）", false, ctx -> {
            if (ctx.args() != null) {
                if ("run".equalsIgnoreCase(ctx.args().trim())) {
                    MemoryExtractor.Outcome outcome = ctx.memoryManager().extractNow();
                    if (outcome.failed()) {
                        emit(ctx.ui(), List.of("记忆提取失败（未写入任何文件）"));
                    } else if (outcome.nothing()) {
                        emit(ctx.ui(), List.of("（没有值得记忆的内容）"));
                    } else {
                        emit(ctx.ui(), List.of("记忆提取完成：新增 " + outcome.created()
                                + " · 更新 " + outcome.updated() + " · 删除 " + outcome.deleted()));
                    }
                    return CommandResult.CONTINUE;
                }
                emit(ctx.ui(), List.of("用法：/memory（弹出指令文件菜单）｜ /memory run（立即提取长期记忆）"));
                return CommandResult.CONTINUE;
            }
            memoryMenu(ctx, Path.of(System.getProperty("user.home")));
            return CommandResult.CONTINUE;
        });
    }

    /** /permission：无参数列举当前模式与三层规则（含文件路径）；参数为四档模式之一时切档（大小写敏感），规则只读 */
    static Command permission() {
        return new Command("permission", List.of(), "查看权限规则；带参数切换模式", "/permission",
                CommandType.LOCAL,
                "<模式>；只接受四档模式之一（default/acceptEdits/plan/bypassPermissions），规则只读",
                false, ctx -> {
            if (ctx.args() == null) {
                emit(ctx.ui(), permissionListLines(ctx));
                return CommandResult.CONTINUE;
            }
            PermissionMode mode = PermissionMode.fromConfig(ctx.args().trim());
            if (mode == null) {
                emit(ctx.ui(), List.of("用法：/permission <模式>（default/acceptEdits/plan/bypassPermissions）"));
                return CommandResult.CONTINUE;
            }
            ctx.permissionChecker().setMode(mode);
            emit(ctx.ui(), List.of("（已切换到权限模式：" + mode.configValue() + "）"));
            return CommandResult.CONTINUE;
        });
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

    // ---- /resume 渲染 ----

    /** 会话菜单条目文案：id + 条数 +（已过期）+ 首条用户消息预览（与既有会话选择入口同款） */
    private static String sessionLabel(Session session) {
        return session.id() + "  " + session.messages().size() + " 条"
                + (session.expired() ? "（已过期）" : "") + " · " + sessionPreview(session);
    }

    private static String sessionPreview(Session session) {
        for (ChatMessage message : session.messages()) {
            if (message.role() == ChatMessage.Role.USER) {
                String text = message.content().replace('\n', ' ').trim();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        return "（无用户消息）";
    }

    // ---- /memory 渲染 ----

    /** 三层指令文件菜单：三层 + 不可选分隔行 + 查看长期记忆；选中/取消分派 */
    private static void memoryMenu(CommandContext ctx, Path userHome) {
        List<ProjectInstructions.Layer> layers =
                ProjectInstructions.layers(ctx.workingDirectory(), userHome);
        List<ProjectInstructions.LayerState> states =
                layers.stream().map(ProjectInstructions.Layer::state).toList();
        List<String> paths = states.stream()
                .map(s -> shortPath(s.path(), ctx.workingDirectory(), userHome)).toList();
        int labelWidth = states.stream().mapToInt(s -> s.menuLabel().length()).max().orElse(0);
        int pathWidth = paths.stream().mapToInt(String::length).max().orElse(0);

        List<MenuEntry> entries = new ArrayList<>(5);
        for (int i = 0; i < states.size(); i++) {
            ProjectInstructions.LayerState state = states.get(i);
            String status = state.exists() ? "已存在 " + state.lines() + " 行" : "未创建";
            String label = state.menuLabel() + " ".repeat(labelWidth - state.menuLabel().length() + 2)
                    + paths.get(i) + " ".repeat(pathWidth - paths.get(i).length() + 2) + status;
            entries.add(MenuEntry.item(label));
        }
        entries.add(MenuEntry.separator());
        entries.add(MenuEntry.item("查看长期记忆（" + ctx.memoryManager().store().listAll().size() + " 条）"));

        int selected = ctx.ui().selectMenu(entries, "（↑/↓ 选择，回车确认，Esc 取消）");
        if (selected == layers.size() + 1) {
            emit(ctx.ui(), memoryListLines(ctx.memoryManager().store()));
        } else if (selected >= 0 && selected < layers.size()) {
            ProjectInstructions.Layer layer = layers.get(selected);
            ProjectInstructions.LayerState state = layer.state();
            if (state.exists()) {
                List<String> lines = new ArrayList<>();
                lines.add(state.path() + "（" + state.lines() + " 行）");
                lines.addAll(readLines(state.path()));
                emit(ctx.ui(), lines);
            } else if (layer.createEmpty()) {
                emit(ctx.ui(), List.of(state.path() + "（已创建空文件）",
                        "用编辑器写入；指令文件的改动下个会话生效"));
            } else {
                emit(ctx.ui(), List.of("创建失败：" + state.path()));
            }
        } else {
            emit(ctx.ui(), List.of("（已取消）"));
        }
    }

    /** 长期记忆列举：按类别分组、项目级在前；无记忆时只有「（暂无记忆）」 */
    private static List<String> memoryListLines(MemoryStore store) {
        List<MemoryFile> memories = store.listAll();
        if (memories.isEmpty()) {
            return List.of("（暂无记忆）");
        }
        List<String> lines = new ArrayList<>();
        lines.add("长期记忆（" + memories.size() + " 条）");
        for (MemoryType type : MEMORY_VIEW_ORDER) {
            List<MemoryFile> group = memories.stream().filter(m -> m.type() == type).toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add(type.slug() + "：");
            for (MemoryFile memory : group) {
                lines.add("  " + memory.name() + " — " + memory.description());
            }
        }
        return lines;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of("读取失败：" + e.getMessage());
        }
    }

    // ---- /permission 渲染 ----

    /** 三层规则的完整状态：当前模式 + 每层文件路径、条数与规则摘要（工具名(模式) → 效果） */
    private static List<String> permissionListLines(CommandContext ctx) {
        Path userHome = Path.of(System.getProperty("user.home"));
        List<RuleEngine.LayerRules> layers = ctx.permissionChecker().ruleLayers();
        List<String> labels = List.of("用户级", "项目级", "项目本地");
        List<String> paths = layers.stream()
                .map(l -> shortPath(l.file(), ctx.workingDirectory(), userHome)).toList();
        int pathWidth = paths.stream().mapToInt(String::length).max().orElse(0);

        List<String> lines = new ArrayList<>();
        lines.add("当前权限模式：" + ctx.permissionChecker().mode().configValue());
        for (int i = 0; i < layers.size(); i++) {
            RuleEngine.LayerRules layer = layers.get(i);
            lines.add(labels.get(i) + "  " + paths.get(i)
                    + " ".repeat(pathWidth - paths.get(i).length() + 2)
                    + layer.count() + " 条");
            for (PermissionRule rule : layer.rules()) {
                lines.add("  " + rule.toolName() + "(" + rule.pattern() + ") → " + rule.effect().keyword());
            }
        }
        return lines;
    }

    // ---- 共用渲染 ----

    /** 菜单用简写路径：用户主目录 → ~/、项目根 → <项目根>/（分隔符统一为正斜杠） */
    private static String shortPath(Path path, Path projectRoot, Path userHome) {
        if (path.startsWith(userHome)) {
            return "~/" + pathString(userHome.relativize(path));
        }
        if (path.startsWith(projectRoot)) {
            return "<项目根>/" + pathString(projectRoot.relativize(path));
        }
        return pathString(path);
    }

    private static String pathString(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void emit(UIController ui, List<String> lines) {
        for (String line : lines) {
            ui.appendSystemMessage(line);
        }
    }
}
