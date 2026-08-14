package com.acode.agent;

import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.conversation.Conversation;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 循环本体：一轮 = 请求模型 → 流式收集 → 有工具调用则执行回填 → 下一轮；
 * 无工具调用则结束。五种终止条件：自然收尾 / 轮数上限 / 用户取消 / 计划交付（T8）/
 * 流错误。run() 在虚拟线程跑循环并返回事件队列，UI 订阅事件渲染。
 */
public class Agent {

    /** 五种循环终止原因 */
    public enum Termination { NORMAL, MAX_ITERATIONS, CANCELED, PLAN_DELIVERED, ERROR }

    /** 工具结果入历史前的截断上限（字符） */
    static final int MAX_TOOL_RESULT_HISTORY_CHARS = 2000;

    /** 输出截断恢复次数上限（超出按正常终止） */
    private static final int MAX_TRUNCATION_RECOVERY = 3;

    /** 可重试流错误重试上限（不含首次请求） */
    private static final int MAX_RETRIES = 2;

    static final String TRUNCATION_CONTINUE_HINT = "输出被截断，请从断点继续，不要重复已输出内容";

    /** 计划交付工具名称（T8） */
    static final String EXIT_PLAN_MODE = "ExitPlanMode";

    private final ChatProvider provider;
    private final Conversation conversation;
    private final ToolRegistry registry;
    private final ToolContext context;
    private final ToolContext planContext;
    private final PlanWriter planWriter = new PlanWriter();
    private final ExitPlanModeTool exitPlanMode = new ExitPlanModeTool();
    private final int maxIterations;

    private final BlockingQueue<AgentEvent> events =
            new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Termination termination = Termination.NORMAL;
    private volatile int totalTurns = 0;
    private int recoveryCount = 0;

    private volatile boolean planMode = false;
    private volatile Path planPath;

    private Thread loopThread;

    public Agent(ChatProvider provider, Conversation conversation,
                 ToolRegistry registry, ToolContext context, int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须为正数：" + maxIterations);
        }
        this.provider = provider;
        this.conversation = conversation;
        this.registry = registry;
        this.context = context;
        this.planContext = new ToolContext(context.workingDirectory(), true);
        this.maxIterations = maxIterations;
        if (registry.available(EXIT_PLAN_MODE) == null) {
            registry.register(exitPlanMode);
        }
        conversation.setTools(registry.availableList());
    }

    /** 虚拟线程跑循环，返回事件队列（调用方随即订阅） */
    public BlockingQueue<AgentEvent> run() {
        running.set(true);
        loopThread = Thread.ofVirtual().name("acode-agent").start(() -> {
            try {
                loop();
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
                conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, TRUNCATION_CONTINUE_HINT));
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
                    conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, collector.text()));
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
     * 流式请求一轮：worker 线程驱动 provider，循环线程 20ms 轮询取消信号。
     * 返回 true 表示流式过程中被取消。
     */
    private boolean stream(ChatRequest request, TurnCollector collector) {
        Thread worker = new Thread(() -> {
            try {
                provider.streamChat(request, collector);
            } catch (RuntimeException e) {
                if (!cancelled.get()) {
                    collector.onError(new ProviderException("生成过程异常：" + e.getMessage(), e));
                }
            }
        }, "acode-provider");
        worker.setDaemon(true);
        worker.start();
        while (worker.isAlive() && !cancelled.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (cancelled.get()) {
            worker.interrupt();
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
        conversation.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, blocks));
    }

    /** 执行工具并把结果（按声明顺序、入历史前截断）回填为 user tool_result 消息 */
    private void executeTools(List<ToolUseBlock> toolUses) {
        if (toolUses.isEmpty()) {
            return;
        }
        StreamingToolExecutor executor = new StreamingToolExecutor(registry, planMode ? planContext : context);
        List<ToolResult> results = executor.execute(toolUses, events, cancelled);
        List<ToolResultBlock> blocks = new ArrayList<>(results.size());
        for (int i = 0; i < toolUses.size(); i++) {
            ToolResult result = results.get(i);
            blocks.add(new ToolResultBlock(toolUses.get(i).id(),
                    truncateForHistory(result.content()), result.isError()));
        }
        conversation.addToolResults(blocks);
    }

    /** 取消时未执行的调用补「已取消」结果入历史（R5） */
    private void addCancelledResults(List<ToolUseBlock> toolUses) {
        List<ToolResultBlock> blocks = new ArrayList<>(toolUses.size());
        for (ToolUseBlock use : toolUses) {
            blocks.add(new ToolResultBlock(use.id(), "已取消", true));
        }
        conversation.addToolResults(blocks);
    }

    /** 按 plan 模式组装请求：工具列表动态过滤 + 系统提醒注入（仅进请求不进历史） */
    private ChatRequest buildPlanAwareRequest(int turn) {
        if (planMode) {
            return conversation.buildRequest(planTools(),
                    ChatMessage.of(ChatMessage.Role.SYSTEM, PlanModePrompt.buildReminder(turn)));
        }
        return conversation.buildRequest(normalTools(), null);
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

    /** 超长工具结果入历史前截断 */
    static String truncateForHistory(String text) {
        if (text == null || text.length() <= MAX_TOOL_RESULT_HISTORY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_RESULT_HISTORY_CHARS) + "\n…（结果过长，已截断）";
    }

    private void emit(AgentEvent event) {
        events.offer(event);
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
