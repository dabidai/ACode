package com.acode.subagent;

import com.acode.agent.*;
import com.acode.conversation.Conversation;
import com.acode.hook.HookEngine;
import com.acode.permission.*;
import com.acode.provider.*;
import com.acode.tool.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** Synchronous non-interactive execution. Only this caller drains the child's events. */
public final class SubAgentRunner {
    private final ChatProvider provider;
    private final Conversation parent;
    private final ToolRegistry tools;
    private final Supplier<PermissionChecker> permissions;
    private final Supplier<HookEngine> hooks;
    private final Supplier<Set<String>> allowedTools;

    public SubAgentRunner(ChatProvider provider, Conversation parent, ToolRegistry tools,
                          Supplier<PermissionChecker> permissions, Supplier<HookEngine> hooks) {
        this(provider, parent, tools, permissions, hooks, () -> null);
    }
    public SubAgentRunner(ChatProvider provider, Conversation parent, ToolRegistry tools,
                          Supplier<PermissionChecker> permissions, Supplier<HookEngine> hooks,
                          Supplier<Set<String>> allowedTools) {
        this.provider = provider; this.parent = parent; this.tools = tools;
        this.permissions = permissions; this.hooks = hooks;
        this.allowedTools = allowedTools;
    }
    public Conversation parent() { return parent; }

    public ToolResult run(AgentDefinition definition, String prompt, String label, String model, Path root) {
        return run(definition, prompt, label, model, root, null, call -> false);
    }

    /** Approval is an explicit per-dispatch scope, never inferred from the task text or parent history. */
    public ToolResult run(AgentDefinition definition, String prompt, String label, String model, Path root,
                          List<ChatMessage> history, Predicate<ToolUseBlock> approval) {
        boolean fork = definition == null;
        String type = fork ? "Fork" : definition.agentType();
        int turns = fork ? 20 : definition.maxTurns();
        Agent agent = null;
        HookEngine childHooks = null;
        try {
            var selected = ToolFilter.filter(tools, definition, fork);
            Set<String> ceiling = allowedTools.get();
            if (ceiling != null) selected = selected.stream().filter(t -> ceiling.contains(t.name())).toList();
            String requestedModel = model == null || model.isBlank() ? (fork ? "inherit" : definition.model()) : model;
            String childModel = fork || requestedModel.equals("inherit") ? parent.model() : requestedModel;
            var conversation = new Conversation(childModel, parent.thinking(), parent.maxTokens(), parent.maxContextTokens());
            conversation.setSystemPrompt(fork ? parent.systemPrompt() : definition.systemPrompt());
            conversation.setEnvironment(parent.environment());
            if (fork || history != null) conversation.replaceAll(copy(history == null ? parent.history() : history));
            conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, (fork ? ForkBoilerplate.TEXT + "\n\n" : "") + prompt));
            var registry = new ToolRegistry();
            // LoadSkill carries session state; give the child its own runtime and registry boundary.
            com.acode.skill.SkillRuntime skills = null;
            for (Tool tool : selected) {
                if (tool instanceof com.acode.skill.LoadSkillTool load) {
                    skills = load.childRuntime(registry, conversation);
                    registry.register(new com.acode.skill.LoadSkillTool(skills));
                } else registry.register(tool);
            }
            var context = new ToolContext(root);
            context.setFork(fork);
            context.setSubAgent(true);
            PermissionChecker parentChecker = permissions.get();
            if (parentChecker == null) return ToolResult.failure("子 Agent「" + type + "」执行失败：缺少权限检查器");
            var checker = parentChecker.child(fork ? parentChecker.mode() : definition.permissionMode());
            agent = new Agent(provider, conversation, registry, context, turns);
            agent.setSkillRuntime(skills);
            agent.setPermissionChecker(checker);
            agent.setConfirmationGate((call, events, cancelled) -> approval.test(call) ? PermissionResponse.ALLOW : PermissionResponse.DENY);
            HookEngine engine = hooks == null ? null : hooks.get();
            if (engine != null) {
                childHooks = engine.childScope(checker);
                agent.setHookEngine(childHooks, prompt);
            }
            var events = agent.run();
            StringBuilder current = new StringBuilder();
            String last = "", error = "未知错误";
            while (agent.isRunning() || !events.isEmpty()) {
                var event = events.poll(50, TimeUnit.MILLISECONDS);
                if (event instanceof AgentEvent.StreamText delta) current.append(delta.text());
                else if (event instanceof AgentEvent.TurnComplete) { last = current.toString(); current.setLength(0); }
                else if (event instanceof AgentEvent.RetryEvent) current.setLength(0);
                else if (event instanceof AgentEvent.ErrorEvent failure) error = failure.message();
            }
            agent.awaitTermination();
            if (agent.termination() == Agent.Termination.ERROR) return ToolResult.failure("子 Agent「" + type + "」执行失败：" + error);
            if (agent.termination() == Agent.Termination.CANCELED) return ToolResult.failure("子 Agent「" + type + "」执行失败：已取消");
            boolean capped = agent.termination() == Agent.Termination.MAX_ITERATIONS;
            String text = current.toString();
            if (capped && text.isEmpty()) text = last;
            if (capped && text.isEmpty()) return ToolResult.failure("子 Agent「" + type + "」达到最大轮数（" + turns + "）且未产出结果");
            return ToolResult.success(text).withDisplay("完成子任务「" + label + "」（" + type + (capped ? "，达到最大轮数 " + turns : "") + "）");
        } catch (InterruptedException e) {
            if (agent != null) { agent.cancel(); agent.awaitTermination(); }
            Thread.currentThread().interrupt();
            return ToolResult.failure("子 Agent「" + type + "」执行失败：已取消");
        } catch (RuntimeException e) {
            return ToolResult.failure("子 Agent「" + type + "」执行失败：" + e.getMessage());
        } finally {
            if (childHooks != null) childHooks.close();
        }
    }
    private static List<ChatMessage> copy(List<ChatMessage> history) {
        return List.copyOf(history).stream().map(message -> new ChatMessage(message.role(), message.blocks().stream()
                .map(block -> block instanceof ToolUseBlock use
                        ? (ContentBlock) new ToolUseBlock(use.id(), use.name(), use.input().deepCopy()) : block).toList())).toList();
    }
}
