package com.acode.agent;

import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.Notice;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.context.CompactExecutor;
import com.acode.context.ContextManager;
import com.acode.context.ContextTooLong;
import com.acode.context.ToolResultBudget;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.permission.PermissionChecker;
import com.acode.prompt.SystemReminder;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.ProviderException;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.RetryPolicy;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.util.VirtualThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 循环本体：一轮 = 请求模型 → 流式收集 → 有工具调用则执行回填 → 下一轮；
 * 无工具调用则结束。五种终止条件：自然收尾 / 轮数上限 / 用户取消 / 计划交付 /
 * 流错误。run() 在虚拟线程跑循环并返回事件队列，UI 订阅事件渲染。
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /** 五种循环终止原因 */
    public enum Termination { NORMAL, MAX_ITERATIONS, CANCELED, PLAN_DELIVERED, ERROR }

    /** 输出截断恢复次数上限（超出按正常终止） */
    private static final int MAX_TRUNCATION_RECOVERY = 3;

    /** 可重试流错误重试上限（不含首次请求） */
    private static final int MAX_RETRIES = 2;

    static final String TRUNCATION_CONTINUE_HINT = "输出被截断，请从断点继续，不要重复已输出内容";

    /** 计划交付工具名称 */
    static final String EXIT_PLAN_MODE = "ExitPlanMode";

    private final ChatProvider provider;
    private final Conversation conversation;
    private final ToolRegistry registry;
    private final ToolContext context;
    private final ToolContext planContext;
    private final PlanWriter planWriter = new PlanWriter();
    private final ExitPlanModeTool exitPlanMode = new ExitPlanModeTool();
    private final int maxIterations;

    /** 上下文管理门面（可空：存量构造/测试不装配则"超长结果全文直存进历史"；新流程必有） */
    private final ContextManager contextManager;

    /** 本 exchange 内是否已强制压缩过（紧急压缩只做一次，防死循环） */
    private boolean forceCompacted;

    private final BlockingQueue<AgentEvent> events =
            new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Termination termination = Termination.NORMAL;
    private volatile int totalTurns = 0;
    private int recoveryCount = 0;

    /** 工具确认门槛：默认放行；UI 装配时替换为事件握手实现 */
    private ConfirmationGate confirmationGate = ConfirmationGate.ALWAYS_ALLOW;

    /** 构造时捕获的历史代次：循环内所有历史写入带此代次，被新 exchange 取代后迟到写入被忽略 */
    private final long epoch;

    /** 权限检查器：UI 装配时注入；null 时执行器走旧确认路径（存量测试兼容） */
    private PermissionChecker permissionChecker;

    /** 一次性轮次提醒（恢复会话后首轮用）：只在本次 exchange 的首轮请求里尾插，不进历史 */
    private ChatMessage oneShotReminder;

    /** 记忆装配门面（可空：存量构造/测试不装配则不做提取）；每轮自然结束后触发一次异步提取 */
    private MemoryManager memoryManager;

    public void setOneShotReminder(ChatMessage reminder) {
        this.oneShotReminder = reminder;
    }

    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 一轮自然结束（模型给出最终回复、不再调用工具）后触发一次异步记忆提取，并就地取走累积的记忆告警。
     * 提取跑在后台虚拟线程、失败只记日志，绝不能影响本轮收尾；告警在本轮收尾时渲染，
     * 不等下次会话启动（异步提取写出的新告警最迟下一轮浮现）。
     */
    private void notifyTurnComplete() {
        if (memoryManager == null) {
            return;
        }
        try {
            memoryManager.onTurnComplete();
            for (String warning : memoryManager.drainWarnings()) {
                emit(new Notice(warning));
            }
        } catch (RuntimeException e) {
            log.warn("记忆收尾失败：{}", e.getMessage());
        }
    }

    public void setConfirmationGate(ConfirmationGate gate) {
        if (gate != null) {
            this.confirmationGate = gate;
        }
    }

    public void setPermissionChecker(PermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    private volatile boolean planMode = false;
    private volatile Path planPath;

    private Thread loopThread;

    public Agent(ChatProvider provider, Conversation conversation,
                 ToolRegistry registry, ToolContext context, int maxIterations) {
        this(provider, conversation, registry, context, maxIterations, null);
    }

    public Agent(ChatProvider provider, Conversation conversation,
                 ToolRegistry registry, ToolContext context, int maxIterations,
                 ContextManager contextManager) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须为正数：" + maxIterations);
        }
        this.provider = provider;
        this.conversation = conversation;
        this.registry = registry;
        this.context = context;
        this.planContext = new ToolContext(context.workingDirectory(), true);
        this.maxIterations = maxIterations;
        this.contextManager = contextManager;
        this.epoch = conversation.currentEpoch();
    }

    /** 虚拟线程跑循环，返回事件队列（调用方随即订阅） */
    public BlockingQueue<AgentEvent> run() {
        running.set(true);
        loopThread = Thread.ofVirtual().name("acode-agent").start(() -> {
            try {
                loop();
            } catch (RuntimeException e) {
                // 顶层兜底：未捕获异常转 ERROR 终止并通知 UI，避免虚拟线程静默死亡、用户无感知
                termination = Termination.ERROR;
                emit(new ErrorEvent("循环异常：" + e.getMessage()));
                emit(new LoopComplete(totalTurns));
            } finally {
                running.set(false);
            }
        });
        return events;
    }

    /** 循环是否仍在运行；取消等不吐 LoopComplete 的收尾需轮询此状态判断循环已结束 */
    public boolean isRunning() {
        return running.get();
    }

    /** 用户取消：置位取消标志并中断循环线程 */
    public void cancel() {
        cancelled.set(true);
        Thread thread = loopThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 等循环线程真实退出；中断只请求取消，不能把尚在执行的工具当作已结束。 */
    public void awaitTermination() {
        Thread thread = loopThread;
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
                cancel();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 循环结束后的终止原因；循环进行中返回初始值 NORMAL */
    public Termination termination() {
        return termination;
    }

    /** 供上层（UI/测试）检查对话历史 */
    public Conversation conversation() {
        return conversation;
    }

    /** 切换 plan 模式：请求只发读类 + ExitPlanMode 工具，每轮注入系统提醒 */
    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    /** 计划交付后的落盘路径；未交付时返回 null */
    public Path planPath() {
        return planPath;
    }

    private void loop() {
        for (int turn = 1; turn <= maxIterations; turn++) {
            if (cancelled.get()) {
                termination = Termination.CANCELED;
                return;
            }
            TurnOutcome outcome = runTurn(turn);
            switch (outcome.kind) {
                case CONTINUE, TRUNCATED -> { /* 继续下一轮 */ }
                case NORMAL_END -> {
                    totalTurns = turn;
                    termination = Termination.NORMAL;
                    notifyTurnComplete();
                    emit(new LoopComplete(turn));
                    return;
                }
                case MAX_HIT -> {
                    totalTurns = turn;
                    termination = Termination.MAX_ITERATIONS;
                    emit(new LoopComplete(turn));
                    return;
                }
                case CANCELED -> {
                    termination = Termination.CANCELED;
                    return;
                }
                case PLAN_DELIVERED -> {
                    totalTurns = turn;
                    termination = Termination.PLAN_DELIVERED;
                    emit(new LoopComplete(turn));
                    return;
                }
                case ERROR -> {
                    totalTurns = turn;
                    termination = Termination.ERROR;
                    emit(new LoopComplete(turn));
                    return;
                }
            }
            totalTurns = turn;
            emit(new TurnComplete(turn));
        }
        // 循环自然耗尽（最后轮截断恢复后无后续轮）：按触顶终止
        totalTurns = maxIterations;
        termination = Termination.MAX_ITERATIONS;
        emit(new LoopComplete(maxIterations));
    }

    private TurnOutcome runTurn(int turn) {
        autoCompactIfNeeded();
        int retries = 0;
        while (true) {
            if (cancelled.get()) {
                return TurnOutcome.cancelled();
            }
            TurnCollector collector = new TurnCollector(events, cancelled);
            boolean cancelledDuringStream = stream(buildPlanAwareRequest(turn), collector);

            if (cancelledDuringStream || cancelled.get()) {
                // 历史一致性（R5）：取消前已收集的 tool_use 必须配对结果，防悬空
                if (!collector.toolUses().isEmpty()) {
                    addAssistantMessage(collector.text(), collector.toolUses());
                    addCancelledResults(collector.toolUses());
                }
                return TurnOutcome.cancelled();
            }
            if (collector.error() != null) {
                ProviderException error = collector.error();
                if (RetryPolicy.isRetryable(error) && retries < MAX_RETRIES) {
                    long waitMs = RetryPolicy.backoffMs(retries + 1);
                    emit(new RetryEvent(error.getMessage() != null ? error.getMessage()
                            : error.getClass().getSimpleName(), waitMs));
                    retries++;
                    if (sleep(waitMs)) {
                        return TurnOutcome.cancelled();
                    }
                    continue; // 重试同一轮
                }
                // 紧急压缩：正常请求被"上下文超长"拒绝 → 就地压缩一次 → 用新历史重试原请求一次
                if (!forceCompacted && contextManager != null && ContextTooLong.matches(error)) {
                    forceCompacted = true;
                    emit(new Notice("（请求过长，正在压缩上下文后重试一次…）"));
                    CompactExecutor.Result compact = contextManager.executor().run(true);
                    if (compact.changed()) {
                        continue; // 重建后重试当前 turn（循环顶部重新 buildRequest 取新历史）
                    }
                    if (compact.failed()) {
                        emit(new Notice("（紧急压缩失败：" + compact.reason() + "）"));
                    }
                }
                emit(new ErrorEvent(error.getMessage() != null ? error.getMessage()
                        : error.getClass().getSimpleName()));
                return TurnOutcome.error();
            }

            // 流式收集完成：处理本轮内容
            if (isTruncated(collector.stopReason())) {
                if (recoveryCount >= MAX_TRUNCATION_RECOVERY) {
                    // 第 4 次截断：按正常终止（内容照常入历史与执行，不注入继续提示）
                    addAssistantMessage(collector.text(), collector.toolUses());
                    executeTools(collector.toolUses());
                    if (cancelled.get()) {
                        return TurnOutcome.cancelled();
                    }
                    return TurnOutcome.normalEnd();
                }
                addAssistantMessage(collector.text(), collector.toolUses());
                executeTools(collector.toolUses());
                if (cancelled.get()) {
                    return TurnOutcome.cancelled();
                }
                conversation.addMessage(epoch, ChatMessage.of(ChatMessage.Role.USER, TRUNCATION_CONTINUE_HINT));
                recoveryCount++;
                return TurnOutcome.truncated();
            }

            // plan 模式交付：本轮调用 ExitPlanMode → 执行 + 落盘计划 → 结束循环（PLAN_DELIVERED）
            if (planMode && hasExitPlanMode(collector.toolUses())) {
                addAssistantMessage(collector.text(), collector.toolUses());
                executeTools(collector.toolUses());
                if (cancelled.get()) {
                    return TurnOutcome.cancelled();
                }
                try {
                    planPath = planWriter.savePlan(context.workingDirectory(), collector.text());
                } catch (IOException e) {
                    emit(new ErrorEvent("计划保存失败：" + e.getMessage()));
                    return TurnOutcome.error();
                }
                return TurnOutcome.planDelivered();
            }

            if (collector.toolUses().isEmpty()) {
                // 自然收尾：无工具调用
                if (!collector.text().isEmpty()) {
                    conversation.addMessage(epoch, ChatMessage.of(ChatMessage.Role.ASSISTANT, collector.text()));
                }
                return TurnOutcome.normalEnd();
            }

            // 有工具调用
            if (turn >= maxIterations) {
                // 触顶不执行：本轮 assistant 消息不入历史（避免悬空 tool_use），已完成结果保留
                return TurnOutcome.maxHit();
            }

            addAssistantMessage(collector.text(), collector.toolUses());
            executeTools(collector.toolUses());
            if (cancelled.get()) {
                return TurnOutcome.cancelled();
            }
            return TurnOutcome.continueTurn();
        }
    }

    /**
     * 流式请求一轮：worker 线程驱动 provider，循环线程 join 等待其结束。
     * 取消时 cancel() 中断循环线程 → join 立即抛 InterruptedException，比 20ms 轮询更及时，
     * 且消除「轮询被中断但 !cancelled 时返回 false、worker 仍在写 collector」的竞态。
     * 返回 true 表示流式过程中被取消。
     */
    private boolean stream(ChatRequest request, TurnCollector collector) {
        Future<?> future = VirtualThreads.POOL.submit(() -> {
            try {
                provider.streamChat(request, collector);
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    collector.onError(new ProviderException("生成过程异常：" + e.getMessage(), e));
                }
            }
        });
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("provider 流式任务异常", e.getCause());
        }
        if (cancelled.get()) {
            future.cancel(true); // 直连取消 provider 任务（等价原 worker.interrupt，不再间接延迟）
            return true;
        }
        return false;
    }

    /** 睡眠退避；返回 true 表示被取消（中断） */
    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelled.get();
        }
        return cancelled.get();
    }

    /** assistant 消息（文本 + tool_use 块）入历史；两者皆空则跳过（R8） */
    private void addAssistantMessage(String text, List<ToolUseBlock> toolUses) {
        if ((text == null || text.isEmpty()) && toolUses.isEmpty()) {
            return;
        }
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(new TextBlock(text));
        }
        blocks.addAll(toolUses);
        conversation.addMessage(epoch, new ChatMessage(ChatMessage.Role.ASSISTANT, blocks));
    }

    /** 每轮构建请求前自动触发压缩（守卫已由 executor.needsAutoCompact 把关，避免空跑） */
    private void autoCompactIfNeeded() {
        if (contextManager == null) {
            return;
        }
        CompactExecutor executor = contextManager.executor();
        if (!executor.needsAutoCompact()) {
            return;
        }
        emit(new Notice("（上下文接近上限，正在自动压缩…）"));
        CompactExecutor.Result result = executor.run(false);
        if (result.changed()) {
            emit(new Notice("（已自动压缩：压缩前约 " + result.beforeEstimate()
                    + " → 压缩后约 " + result.afterEstimate() + "）"));
        } else if (result.failed()) {
            emit(new Notice("（自动压缩失败：" + result.reason() + "，本轮继续执行）"));
        }
    }

    /** 执行工具并把结果（按声明顺序，经大结果预算闸门处理）回填为 user tool_result 消息 */
    private void executeTools(List<ToolUseBlock> toolUses) {
        if (toolUses.isEmpty()) {
            return;
        }
        StreamingToolExecutor executor =
                new StreamingToolExecutor(registry, planMode ? planContext : context, permissionChecker, confirmationGate);
        List<ToolResult> results = executor.execute(toolUses, events, cancelled);
        List<ToolResultBlock> blocks;
        if (contextManager != null) {
            // ch07 Layer-1：超长结果落盘 + 定长预览，同批聚合限流（信息不丢，模型可按路径读回）
            List<ToolResultBudget.Item> items = new ArrayList<>(toolUses.size());
            for (int i = 0; i < toolUses.size(); i++) {
                ToolResult result = results.get(i);
                items.add(new ToolResultBudget.Item(toolUses.get(i).id(), result.content(), result.isError()));
            }
            blocks = contextManager.budget().process(items);
        } else {
            // 存量构造/测试：全文直存（不再做旧的 2000 字符一刀切截断）
            blocks = new ArrayList<>(results.size());
            for (int i = 0; i < toolUses.size(); i++) {
                ToolResult result = results.get(i);
                blocks.add(new ToolResultBlock(toolUses.get(i).id(), result.content(), result.isError()));
            }
        }
        conversation.addToolResults(epoch, blocks);
    }

    /** 取消时未执行的调用补「已取消」结果入历史（R5） */
    private void addCancelledResults(List<ToolUseBlock> toolUses) {
        List<ToolResultBlock> blocks = new ArrayList<>(toolUses.size());
        for (ToolUseBlock use : toolUses) {
            blocks.add(new ToolResultBlock(use.id(), "已取消", true));
        }
        conversation.addToolResults(epoch, blocks);
    }

    /** 按 plan 模式组装请求：工具列表动态过滤 + 轮次级 system-reminder 提醒（尾插，仅进请求不进历史） */
    private ChatRequest buildPlanAwareRequest(int turn) {
        if (planMode) {
            return conversation.buildRequest(planTools(),
                    SystemReminder.wrap(PlanModePrompt.buildReminder(turn)));
        }
        // 恢复会话后的一次性提醒只挂首轮（同轮重试仍在首轮内，提醒不丢）
        return conversation.buildRequest(normalTools(), turn == 1 ? oneShotReminder : null);
    }

    /** plan 模式工具列表：读类工具 + ExitPlanMode，各恰好一次 */
    private List<Tool> planTools() {
        List<Tool> result = new ArrayList<>();
        for (Tool tool : registry.availableList()) {
            if (tool.permission() == Permission.READ && !EXIT_PLAN_MODE.equals(tool.name())) {
                result.add(tool);
            }
        }
        result.add(exitPlanMode);
        return result;
    }

    /** 普通模式工具列表：全部可用工具去掉 ExitPlanMode */
    private List<Tool> normalTools() {
        return registry.availableList().stream()
                .filter(tool -> !EXIT_PLAN_MODE.equals(tool.name()))
                .toList();
    }

    private static boolean hasExitPlanMode(List<ToolUseBlock> toolUses) {
        return toolUses.stream().anyMatch(tu -> EXIT_PLAN_MODE.equals(tu.name()));
    }

    private static boolean isTruncated(String stopReason) {
        return "max_tokens".equals(stopReason) || "length".equals(stopReason);
    }

    private void emit(AgentEvent event) {
        AgentEvent.putSafe(events, event);
    }

    private enum TurnKind { CONTINUE, TRUNCATED, NORMAL_END, MAX_HIT, CANCELED, PLAN_DELIVERED, ERROR }

    private record TurnOutcome(TurnKind kind) {
        static TurnOutcome continueTurn() { return new TurnOutcome(TurnKind.CONTINUE); }
        static TurnOutcome truncated() { return new TurnOutcome(TurnKind.TRUNCATED); }
        static TurnOutcome normalEnd() { return new TurnOutcome(TurnKind.NORMAL_END); }
        static TurnOutcome maxHit() { return new TurnOutcome(TurnKind.MAX_HIT); }
        static TurnOutcome cancelled() { return new TurnOutcome(TurnKind.CANCELED); }
        static TurnOutcome planDelivered() { return new TurnOutcome(TurnKind.PLAN_DELIVERED); }
        static TurnOutcome error() { return new TurnOutcome(TurnKind.ERROR); }
    }
}
