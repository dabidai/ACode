package com.acode.hook;

import com.acode.permission.PermissionChecker;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Session-owned dispatcher. Async work keeps the session generation it was dispatched in. */
public final class HookEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HookEngine.class);
    public record FireResult(boolean rejected, String reason) {
        public static FireResult allowed() { return new FireResult(false, ""); }
    }
    @FunctionalInterface public interface Approval { boolean allow(Tool tool, JsonNode args); }
    private final List<HookConfig> hooks;
    private final HookAction actions;
    private final Supplier<PermissionChecker> checker;
    private final Consumer<String> onceSink;
    private final Set<String> once = new HashSet<>();
    private final List<String> prompts = new ArrayList<>();
    private final Set<Future<?>> pending = new HashSet<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private long generation;
    private boolean closed;

    public HookEngine(List<HookConfig> hooks, HookAction actions, Supplier<PermissionChecker> checker, Consumer<String> onceSink) {
        this.hooks = List.copyOf(hooks);
        this.actions = actions;
        this.checker = checker;
        this.onceSink = onceSink;
    }
    public FireResult fire(String event, HookContext context) { return fire(event, context, null); }
    public synchronized FireResult fire(String event, HookContext context, Approval approval) {
        if (closed) return FireResult.allowed();
        for (HookConfig hook : hooks) {
            try {
                if (!hook.event().equals(event) || (hook.once() && once.contains(hook.id())) || !hook.condition().matches(context)) continue;
                if (Thread.currentThread().isInterrupted()) break;
                long dispatched = generation;
                if (hook.async()) {
                    mark(hook);
                    pending.removeIf(Future::isDone);
                    pending.add(workers.submit(() -> {
                        HookAction.Result result = execute(hook, context, null);
                        synchronized (HookEngine.this) { enqueue(hook, result, dispatched); }
                    }));
                } else {
                    HookAction.Result result = execute(hook, context, approval);
                    mark(hook);
                    enqueue(hook, result, dispatched);
                    if (hook.reject() && result.ok()) return new FireResult(true, result.output());
                }
            } catch (Exception e) { log.warn("Hook 执行失败 [{}]：{}", hook.id(), e.toString()); }
        }
        return FireResult.allowed();
    }
    private HookAction.Result execute(HookConfig hook, HookContext context, Approval approval) {
        try {
            if (Thread.currentThread().isInterrupted()) return HookAction.Result.failure("动作已取消");
            if (!authorize(hook, context, approval)) return HookAction.Result.failure("权限未放行，跳过动作");
            var result = actions.execute(hook, context);
            if (!result.ok()) log.warn("Hook 执行失败 [{}]：{}", hook.id(), result.output());
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Hook 执行失败 [{}]：{}", hook.id(), e.toString());
            return HookAction.Result.failure(e.toString());
        }
    }
    private boolean authorize(HookConfig hook, HookContext context, Approval approval) {
        var type = hook.action().type();
        if (type != HookConfig.ActionType.COMMAND && type != HookConfig.ActionType.HTTP) return true;
        boolean command = type == HookConfig.ActionType.COMMAND;
        String field = command ? "command" : "url";
        Tool tool = new ActionPermissionTool(command ? "Bash" : "HookHttp", field);
        var args = JsonNodeFactory.instance.objectNode().put(field, context.expand(hook.action().value(field)));
        if (!command) {
            args.put("method", hook.action().values().getOrDefault("method", "POST"));
            args.put("body", context.expand(hook.action().value("body")));
        }
        PermissionChecker permission = checker == null ? null : checker.get();
        if (permission == null) { log.warn("Hook 执行失败 [{}]：缺少权限检查器，跳过动作", hook.id()); return false; }
        var decision = permission.check(tool, args);
        boolean allowed = switch (decision.decision()) {
            case ALLOW -> true;
            case DENY -> false;
            case ASK -> approval != null && approval.allow(tool, args);
        };
        if (!allowed) log.warn("Hook 执行失败 [{}]：权限未放行或无交互入口，跳过动作", hook.id());
        return allowed;
    }
    private void mark(HookConfig hook) {
        if (hook.once() && once.add(hook.id()) && onceSink != null) {
            try { onceSink.accept(hook.id()); }
            catch (Exception e) { log.warn("Hook once 持久化失败 [{}]：{}", hook.id(), e.toString()); }
        }
    }
    private void enqueue(HookConfig hook, HookAction.Result result, long dispatched) {
        if (!closed && dispatched == generation && result.ok() && hook.action().type() == HookConfig.ActionType.PROMPT)
            prompts.add(result.output());
    }
    public synchronized List<String> drainPrompts() {
        var result = List.copyOf(prompts); prompts.clear(); return result;
    }
    public synchronized void loadOnceIds(Set<String> ids) {
        generation++;
        pending.forEach(it -> it.cancel(true)); pending.clear();
        once.clear(); once.addAll(ids); prompts.clear();
    }
    @Override public synchronized void close() {
        closed = true; generation++; prompts.clear();
        pending.forEach(it -> it.cancel(true)); pending.clear(); workers.shutdownNow();
    }
    private record ActionPermissionTool(String name, String contentField) implements Tool {
        public String description() { return "Hook 自动动作"; }
        public Permission permission() { return Permission.EXEC; }
        public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
        public ToolResult execute(JsonNode args, ToolContext context) { throw new UnsupportedOperationException(); }
    }
}
