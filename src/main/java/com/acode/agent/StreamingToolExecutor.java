package com.acode.agent;

import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionChecker.CheckResult;
import com.acode.permission.PermissionResponse;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolExecutor;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具分区执行器：按权限分区——读类并发（虚拟线程）、写类与命令类串行且保持声明顺序，
 * 全部读类先执行。结果 List 按输入 index 落位（回传顺序 = 声明顺序）。每完成一个调用
 * 发一条 ToolResultEvent；cancelled 置位时未执行/未完成的调用补「已取消」结果。
 * 阶段五：执行前插入权限检查——DENY 返回「权限拒绝」错误结果、ASK 走确认门槛、
 * ALLOW 直接执行；「始终允许」记会话 + 持久化本地规则（写盘失败仅警告，不阻断）。
 */
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolContext context;
    private final ToolExecutor executor;
    private final ConfirmationGate confirmationGate;
    private final PermissionChecker permissionChecker;

    /** @deprecated 仅保留给存量测试（无权限检查）；生产装配路径请用带 checker 的构造器。 */
    @Deprecated
    public StreamingToolExecutor(ToolRegistry registry, ToolContext context) {
        this(registry, context, null, ConfirmationGate.ALWAYS_ALLOW);
    }

    /** @deprecated 仅保留给存量测试（无权限检查）；生产装配路径请用带 checker 的构造器。 */
    @Deprecated
    public StreamingToolExecutor(ToolRegistry registry, ToolContext context, ConfirmationGate confirmationGate) {
        this(registry, context, null, confirmationGate);
    }

    public StreamingToolExecutor(ToolRegistry registry, ToolContext context,
                                 PermissionChecker permissionChecker, ConfirmationGate confirmationGate) {
        this.registry = registry;
        this.context = context;
        this.executor = new ToolExecutor(registry, context);
        this.permissionChecker = permissionChecker;
        this.confirmationGate = confirmationGate;
    }

    /**
     * @return 结果 List，长度与输入一致、按输入声明顺序对齐
     */
    public List<ToolResult> execute(List<ToolUseBlock> calls,
                                    BlockingQueue<AgentEvent> events,
                                    AtomicBoolean cancelled) {
        ToolResult[] results = new ToolResult[calls.size()];
        if (calls.isEmpty()) {
            return List.of();
        }
        if (cancelled.get()) {
            return fillCancelled(results);
        }

        List<Integer> readIndexes = new ArrayList<>();
        List<Integer> serialIndexes = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Tool tool = registry.available(calls.get(i).name());
            if (tool != null && tool.permission() == Permission.READ) {
                readIndexes.add(i);
            } else {
                serialIndexes.add(i);
            }
        }

        // 读组：>1 真实并发（虚拟线程）；≤1 直接执行
        if (readIndexes.size() > 1) {
            runConcurrently(readIndexes, calls, results, events, cancelled);
        } else if (readIndexes.size() == 1) {
            runCall(readIndexes.get(0), calls, results, events, cancelled);
        }

        // 串行组：按声明顺序逐个执行；取消后剩余不跑
        if (!cancelled.get()) {
            for (int index : serialIndexes) {
                if (cancelled.get()) {
                    break;
                }
                runCall(index, calls, results, events, cancelled);
            }
        }

        return fillCancelled(results);
    }

    private void runConcurrently(List<Integer> indexes, List<ToolUseBlock> calls,
                                 ToolResult[] results, BlockingQueue<AgentEvent> events,
                                 AtomicBoolean cancelled) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index : indexes) {
                futures.add(pool.submit(() -> runCall(index, calls, results, events, cancelled)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // runCall 不抛异常，理论上不可达；防御性捕获避免吞掉虚拟线程异常
            throw new IllegalStateException("工具执行线程异常", e.getCause());
        }
    }

    private void runCall(int index, List<ToolUseBlock> calls, ToolResult[] results,
                         BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        ToolUseBlock call = calls.get(index);
        Tool tool = registry.available(call.name());
        if (tool != null && permissionChecker != null) {
            CheckResult decision = permissionChecker.check(tool, call.input());
            switch (decision.decision()) {
                case DENY -> {
                    failAndEmit(call, results, index, events, "权限拒绝：" + decision.reason());
                    return;
                }
                case ASK -> {
                    PermissionResponse response = confirmationGate.confirm(call, events, cancelled);
                    if (response == PermissionResponse.DENY) {
                        failAndEmit(call, results, index, events, "用户拒绝执行「" + call.name() + "」");
                        return;
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        rememberAlwaysAllow(tool, call);
                    }
                }
                case ALLOW -> { /* 直接执行 */ }
            }
        } else if (tool != null && tool.permission() != Permission.READ
                && confirmationGate.confirm(call, events, cancelled) == PermissionResponse.DENY) {
            // 兼容旧行为：无 checker 时非 READ 工具走确认门槛
            failAndEmit(call, results, index, events, "用户拒绝执行「" + call.name() + "」");
            return;
        }
        if (tool instanceof InteractiveTool interactive) {
            ToolResult result = interactive.executeInteractive(call, events, cancelled);
            if (cancelled.get()) {
                return; // fillCancelled 兜底「已取消」
            }
            results[index] = result;
            // 交互耗时记 0：不含用户思考时间
            AgentEvent.putSafe(events, new ToolResultEvent(call.id(), call.name(),
                    result.content(), result.isError(), 0, result.display()));
            return;
        }
        long start = System.nanoTime();
        ToolResult result = executor.execute(call.name(), call.input());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (cancelled.get()) {
            return;
        }
        results[index] = result;
        AgentEvent.putSafe(events, new ToolResultEvent(call.id(), call.name(),
                result.content(), result.isError(), elapsedMs, result.display()));
    }

    /** 拒绝路径：写错误结果 + 发事件（耗时记 0，不含用户确认思考时间）。 */
    private void failAndEmit(ToolUseBlock call, ToolResult[] results, int index,
                             BlockingQueue<AgentEvent> events, String message) {
        results[index] = ToolResult.failure(message);
        AgentEvent.putSafe(events, new ToolResultEvent(call.id(), call.name(),
                results[index].content(), true, 0, results[index].display()));
    }

    /** 「始终允许」：会话级即时生效 + 本地规则文件持久化（R9：写回失败仅警告，不阻断执行）。 */
    private void rememberAlwaysAllow(Tool tool, ToolUseBlock call) {
        String content = PermissionChecker.extractContent(tool, call.input());
        if (content == null) {
            return;
        }
        permissionChecker.addAllowAlwaysRule(tool.name(), content);
        if (!permissionChecker.appendLocalRule(tool.name(), content)) {
            log.warn("「始终允许」持久化失败，仅会话内生效：{} {}", tool.name(), content);
        }
    }

    private static List<ToolResult> fillCancelled(ToolResult[] results) {
        List<ToolResult> list = new ArrayList<>(results.length);
        for (ToolResult result : results) {
            list.add(result != null ? result : ToolResult.failure("已取消"));
        }
        return list;
    }
}
