package com.acode.team;

import com.acode.agent.Agent;
import com.acode.conversation.Conversation;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.subagent.*;
import com.acode.team.tools.*;
import com.acode.tool.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/** Session-owned integration boundary. No implicit restoration of disk teams. */
public final class TeamSession implements AutoCloseable {
    public interface Worktrees {
        Path create(String name) throws Exception;
        void verifyRemovable(String name) throws Exception;
        void remove(String name) throws Exception;
    }
    public static final String TEAM_PROMPT = "IMPORTANT: You are running as an agent in a team.\n"
            + "Just writing a response in text is not visible to others\non your team - you MUST use the SendMessage tool.\n"
            + "The user interacts primarily with the team lead.\nYour work is coordinated through the task system\nand teammate messaging.";
    private static final Set<String> FORBIDDEN = Set.of("Agent", "TeamCreate", "TeamDelete", "AskUser", "ExitPlanMode");
    private final TeamManager manager;
    private final Path projectRoot;
    private final String lead = "lead-" + UUID.randomUUID();
    private final ChatProvider provider;
    private final Conversation parent;
    private final ToolRegistry parentTools;
    private final Supplier<PermissionChecker> permissions;
    private final Supplier<Set<String>> ceiling;
    private final Worktrees worktrees;
    private final Consumer<String> output;
    private final Map<String, TeammateRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, TeamApproval> approvals = new ConcurrentHashMap<>();
    private final Map<String, AgentNameRegistry> names = new ConcurrentHashMap<>();
    private volatile String selected;
    private boolean closed;
    private Supplier<com.acode.hook.HookEngine> hooks = () -> null;
    public void setHooks(Supplier<com.acode.hook.HookEngine> hooks) { this.hooks = hooks; }

    public TeamSession(Path root, ChatProvider provider, Conversation parent, ToolRegistry parentTools,
                       Supplier<PermissionChecker> permissions, Supplier<Set<String>> ceiling,
                       Worktrees worktrees, Consumer<String> output) {
        projectRoot = root.toAbsolutePath().normalize();
        manager = new TeamManager(root); this.provider = provider; this.parent = parent; this.parentTools = parentTools;
        this.permissions = permissions; this.ceiling = ceiling; this.worktrees = worktrees; this.output = output;
    }
    public TeamManager manager() { return manager; }
    private Team require(String name) { return manager.find(name).orElseThrow(() -> new IllegalArgumentException("团队不存在：" + name)); }
    private Team current() {
        if (selected == null) throw new IllegalArgumentException("请先使用 TeamCreate 创建团队，或通过 Agent 的 team_name 选择团队");
        return require(selected);
    }
    public synchronized Team create(String name, String description) {
        if (closed) throw new IllegalStateException("团队会话已关闭");
        Team team = manager.create(name, lead, description);
        var registry = new AgentNameRegistry(); registry.register(TeamMessaging.LEAD, lead); names.put(team.name(), registry);
        selected = team.name(); return team;
    }
    public List<Tool> leadTools() {
        var list = new ArrayList<Tool>(coordination(this::current, lead));
        list.add(new TeamCreateTool(this::create)); list.add(new TeamDeleteTool(this::delete));
        return List.copyOf(list);
    }
    private List<Tool> coordination(Supplier<Team> team, String actor) {
        Supplier<TeamTaskStore> store = () -> new TeamTaskStore(team.get().configPath().getParent());
        return List.of(new TaskCreateTool(store, actor), new TaskGetTool(store, actor), new TaskListTool(store, actor),
                new TaskUpdateTool(store, actor), new SendMessageTool(new TeamMessaging(team, actor, recipient -> delivered(team.get(), recipient))));
    }
    private synchronized void delivered(Team team, String recipient) {
        if (closed) return;
        if (recipient.equals(TeamMessaging.LEAD)) { output.accept("团队 " + team.name() + "：收到队员消息"); return; }
        String key = team.name() + "/" + recipient;
        var approval = approvals.get(key);
        if (approval != null) {
            for (var message : new FileMailbox(team.configPath().getParent()).unread(recipient)) {
                if (message.from().equals(TeamMessaging.LEAD) && message.messageType().equals("plan_approval_response"))
                    try { approval.reply(message.message()); }
                    catch (IllegalArgumentException e) { output.accept("审批回复无效：" + e.getMessage()); }
            }
        }
        var runtime = runtimes.get(key);
        if (runtime != null) runtime.wake();
    }

    public synchronized ToolResult spawn(String teamName, String name, String prompt, AgentDefinition definition,
                                          String model, boolean approvalRequired) {
        if (closed) return ToolResult.failure("团队会话已关闭");
        String worktreeName = null, key = null;
        boolean registered = false, allocated = false;
        String actor = "agent-" + UUID.randomUUID();
        try {
            Team team = require(teamName);
            TeamManager.validateName(name);
            if (name.equals(TeamMessaging.LEAD) || manager.member(teamName, name).isPresent())
                throw new IllegalArgumentException("队员名称或标识已存在：" + name);
            if (worktrees == null) throw new IllegalStateException("缺少可编程 Worktree 接口");
            var selectedTools = ToolFilter.filter(parentTools, definition, definition == null).stream()
                    .filter(t -> !FORBIDDEN.contains(t.name()) && !TeamApproval.coordination(t.name())).toList();
            Set<String> authorized = ceiling.get();
            selectedTools = selectedTools.stream().filter(t -> authorized == null || authorized.contains(t.name())).toList();
            var checker = Objects.requireNonNull(permissions.get(), "缺少权限检查器");
            worktreeName = "team-" + teamName + "/" + name;
            Path root = worktrees.create(worktreeName);
            allocated = true;
            if (root == null || !java.nio.file.Files.isDirectory(root) || !root.toRealPath().equals(root.toAbsolutePath().normalize())
                    || root.toRealPath().equals(projectRoot.toRealPath())) throw new IllegalStateException("队员 Worktree 路径无效或与主目录相同");
            String requested = model == null || model.isBlank() ? definition == null ? "inherit" : definition.model() : model;
            String childModel = requested.equals("inherit") ? parent.model() : requested;
            var conversation = new Conversation(childModel, parent.thinking(), parent.maxTokens(), parent.maxContextTokens());
            conversation.setSystemPrompt((definition == null ? parent.systemPrompt() : definition.systemPrompt()) + "\n\n" + TEAM_PROMPT
                    + (approvalRequired ? "\n修改前用 SendMessage 向 lead 提交计划，等待包含精确操作的审批回复。" : ""));
            conversation.setEnvironment(parent.environment());
            if (definition == null) conversation.replaceAll(parent.history().stream().map(message -> new ChatMessage(message.role(),
                    message.blocks().stream().map(block -> block instanceof ToolUseBlock use
                            ? (ContentBlock) new ToolUseBlock(use.id(), use.name(), use.input().deepCopy()) : block).toList())).toList());
            conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, prompt));
            var registry = new ToolRegistry();
            var approval = new TeamApproval();
            com.acode.skill.SkillRuntime skills = null;
            for (Tool tool : selectedTools) {
                if (tool instanceof com.acode.skill.LoadSkillTool load) {
                    skills = load.childRuntime(registry, conversation);
                    tool = new com.acode.skill.LoadSkillTool(skills);
                }
                registry.register(approvalRequired && tool.permission() != Permission.READ ? approval.guard(tool) : tool);
            }
            coordination(() -> require(teamName), actor).forEach(registry::register);
            var childSkills = skills;
            var context = new ToolContext(root); context.setSubAgent(true); context.setFork(definition == null);
            var childChecker = checker.child(definition == null ? checker.mode() : definition.permissionMode(), root);
            var childHooks = new java.util.concurrent.atomic.AtomicReference<com.acode.hook.HookEngine>();
            Supplier<Agent> factory = () -> {
                var agent = new Agent(provider, conversation, registry, context, definition == null ? 20 : definition.maxTurns());
                agent.setPermissionChecker(childChecker);
                agent.setSkillRuntime(childSkills);
                var hookEngine = hooks.get();
                if (hookEngine != null) {
                    var scoped = hookEngine.childScope(approvalRequired ? childChecker.readOnly() : childChecker);
                    childHooks.set(scoped);
                    agent.setHookEngine(scoped, prompt);
                }
                agent.setConfirmationGate((call, events, cancelled) -> TeamApproval.coordination(call.name()) || approval.permits(call)
                        ? PermissionResponse.ALLOW : PermissionResponse.DENY);
                if (approvalRequired) agent.setToolCeiling(() -> registry.availableList().stream().filter(approval::exposes)
                        .map(Tool::name).collect(java.util.stream.Collectors.toSet()));
                return agent;
            };
            names.get(teamName).register(name, actor);
            manager.register(teamName, new TeammateInfo(name, actor, definition == null ? null : definition.agentType(), childModel,
                    root, TeammateInfo.BackendType.IN_PROCESS, false, approvalRequired));
            registered = true;
            key = teamName + "/" + name;
            var runtime = new TeammateRuntime(manager, teamName, name, actor, conversation, factory, output, () -> {
                var scoped = childHooks.getAndSet(null);
                if (scoped != null) scoped.close();
            });
            approvals.put(key, approval); runtimes.put(key, runtime);
            runtime.wake(); selected = teamName;
            return ToolResult.success("已派遣队员 " + name + "（" + actor + "），Worktree：" + root);
        } catch (Exception e) {
            if (names.containsKey(teamName)) names.get(teamName).unregister(actor);
            if (key != null) { var runtime = runtimes.remove(key); if (runtime != null) runtime.stop(); approvals.remove(key); }
            if (registered) manager.removeMember(teamName, name);
            // Preserve a failed allocation for diagnosis if safe removal is not possible.
            if (allocated) try { worktrees.verifyRemovable(worktreeName); worktrees.remove(worktreeName); }
                catch (Exception cleanup) { output.accept("保留派生失败的 Worktree：" + worktreeName + "（" + cleanup.getMessage() + "）"); }
            return ToolResult.failure("队员派生失败：" + e.getMessage());
        }
    }

    public synchronized String delete(String name) {
        Team team = require(name);
        for (var member : team.members()) {
            var runtime = runtimes.get(name + "/" + member.name());
            if (!Boolean.FALSE.equals(member.isActive()) || runtime != null && runtime.active())
                throw new IllegalArgumentException("队员 " + member.name() + " 仍在活跃中");
        }
        try {
            for (var member : team.members()) if (member.worktreePath() != null) worktrees.verifyRemovable("team-" + name + "/" + member.name());
            for (var member : team.members()) {
                var runtime = runtimes.get(name + "/" + member.name());
                if (runtime != null && !runtime.stop()) throw new IllegalStateException("队员 " + member.name() + " 未能停止，取消删除");
                if (member.worktreePath() != null) worktrees.remove("team-" + name + "/" + member.name());
            }
            manager.delete(name);
            names.remove(name);
            for (var member : team.members()) { runtimes.remove(name + "/" + member.name()); approvals.remove(name + "/" + member.name()); }
            if (name.equals(selected)) selected = manager.list().stream().map(Team::name).findFirst().orElse(null);
            return "已清理 " + team.members().size() + " 名空闲队员：" + String.join("、", team.members().stream().map(TeammateInfo::name).toList()) + "，团队 " + name + " 已删除";
        } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
    }

    public void configureLead(Agent agent, boolean coordinator) {
        var batches = new java.util.concurrent.atomic.AtomicReference<Map<Path, List<FileMailbox.Message>>>(Map.of());
        agent.setTurnReminderSource(() -> {
            var current = new LinkedHashMap<Path, List<FileMailbox.Message>>();
            var text = new StringBuilder();
            for (Team team : manager.list()) {
                Path path = team.configPath().getParent();
                var messages = new FileMailbox(path).unread(TeamMessaging.LEAD);
                current.put(path, messages);
                if (!messages.isEmpty()) text.append("团队 ").append(team.name()).append("\n").append(TeammateRuntime.render(messages));
            }
            batches.set(current);
            return text.isEmpty() ? null : ChatMessage.of(ChatMessage.Role.USER, text.toString());
        }, () -> batches.get().forEach((path, messages) -> new FileMailbox(path).acknowledge(TeamMessaging.LEAD,
                messages.stream().map(FileMailbox.Message::id).toList())));
        if (coordinator) {
            agent.setToolCeiling(() -> CoordinatorMode.ALLOWED);
            agent.setSystemPromptSuffix(CoordinatorMode.PROMPT);
        }
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        for (var entry : runtimes.entrySet()) if (!entry.getValue().stop()) output.accept("队员 " + entry.getKey() + " 未能停止");
    }
}
