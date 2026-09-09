package com.acode;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.ChoiceRequestEvent;
import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.agent.AgentEvent.UsageEvent;
import com.acode.agent.EventConfirmationGate;
import com.acode.config.AppConfig;
import com.acode.config.ConfigValidator;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionResponse;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.Usage;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import com.acode.ui.StatusBar;
import com.acode.ui.StreamPrinter;
import com.acode.ui.StreamingTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** 单轮 exchange 的执行：追加用户消息 → 建 Agent → 事件轮询分发 → 收尾。 */
public class ExchangeRunner {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRunner.class);

    /** 取消后等循环线程收尾的上限（毫秒）；包可见非 final，测试可调小。超时后旧线程的
     *  残留历史写入由 Conversation 的 epoch 校验忽略，仅记告警，UI 不挂死。 */
    static long awaitLoopEndTimeoutMillis = 5000;

    private final ChatProvider provider;
    private final AppConfig config;
    private final Conversation conversation;
    private final ToolRegistry toolRegistry;
    private final OutputPane output;
    private final RenderContext renderContext;
    private final Function<ConfirmationRequestEvent, PermissionResponse> confirmAnswerer;
    private final Function<ChoiceRequestEvent, String> choiceAnswerer;
    private final Path projectRoot;
    private final Supplier<PermissionChecker> permissionCheckerSupplier;

    public ExchangeRunner(ChatProvider provider, AppConfig config, Conversation conversation,
                          ToolRegistry toolRegistry, OutputPane output, RenderContext renderContext,
                          Function<ConfirmationRequestEvent, PermissionResponse> confirmAnswerer,
                          Function<ChoiceRequestEvent, String> choiceAnswerer,
                          Path projectRoot, Supplier<PermissionChecker> permissionCheckerSupplier) {
        this.provider = provider;
        this.config = config;
        this.conversation = conversation;
        this.toolRegistry = toolRegistry;
        this.output = output;
        this.renderContext = renderContext;
        this.confirmAnswerer = confirmAnswerer;
        this.choiceAnswerer = choiceAnswerer;
        this.projectRoot = projectRoot;
        this.permissionCheckerSupplier = permissionCheckerSupplier;
    }

    /**
     * 单次输入触发 Agent 循环：追加 user 消息 → new Agent(...).run() 在虚拟线程跑 ReAct 循环 →
     * 主线程订阅事件队列逐条渲染（流式文本 / 工具卡片 / 轮次收尾 / 重试 / 错误 / 循环结束提示）。
     * ctrlC 注入中断源（真实终端为 Ctrl+C）；repaint 为保留参数（渲染已全部经活跃区完成）。
     */
    void run(String input, BooleanSupplier ctrlC, Runnable repaint, boolean planMode) {
        conversation.nextEpoch(); // 先失效上一轮残留的 agent 线程写入，再开始本轮
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, input));
        LiveRegionRenderer live = renderContext.liveRenderer();
        Writer writer = renderContext.screenWriter();

        // Echo 用户输入（JLine 已擦除原行，重写 >*input，紧跟无空格）
        output.appendLine(">*" + input);
        live.clearBelowCursor(writer); // 擦掉提示符下方仍在屏上的页脚两行，输出从干净行开始
        live.appendCommitted(writer, ">*" + input);
        printDivider(live, writer); // 3.2 分隔线（回复之前）

        long estimatedInputTokens = estimateCurrentInputTokens();
        long startTime = System.currentTimeMillis();

        Agent agent = new Agent(provider, conversation, toolRegistry,
                new ToolContext(projectRoot), maxIterations());
        agent.setPlanMode(planMode);
        agent.setConfirmationGate(new EventConfirmationGate());
        agent.setPermissionChecker(permissionCheckerSupplier.get());
        BlockingQueue<AgentEvent> events = agent.run();

        StreamPrinter printer = new StreamPrinter(output, live, writer, config.isTeeEnabled());
        StreamingTimer timer = new StreamingTimer(startTime);
        List<ToolResult> turnResults = new ArrayList<>();
        List<Long> elapsedList = new ArrayList<>();
        long totalOutputTokens = 0;
        while (true) {
            if (ctrlC.getAsBoolean()) {
                agent.cancel();
                printer.finishTurn(); // 半截 footer 先转正进回滚，中断提示再追加
                long elapsed = System.currentTimeMillis() - startTime;
                printUsageFootnote(estimatedInputTokens, totalOutputTokens, elapsed, live, writer);
                output.appendLine("（已中断）");
                live.appendCommitted(writer, "（已中断）");
                awaitLoopEnd(agent); // 取消不吐 LoopComplete：等循环线程收尾（补「已取消」）再返回
                renderWaitingFrame(live, writer);
                break;
            }
            AgentEvent event = pollEvent(events);
            if (event == null) {
                if (!agent.isRunning() && events.isEmpty()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    printUsageFootnote(estimatedInputTokens, totalOutputTokens, elapsed, live, writer);
                    renderWaitingFrame(live, writer);
                    break; // 取消等无 LoopComplete 收尾：循环线程结束且事件耗尽即结束
                }
                continue;
            }
            if (event instanceof LoopComplete) {
                long elapsed = System.currentTimeMillis() - startTime;
                printUsageFootnote(estimatedInputTokens, totalOutputTokens, elapsed, live, writer);
                printer.updateToolCalls(turnResults, elapsedList);
                printer.finishTurn();
                completeLoop(agent, live, writer);
                renderWaitingFrame(live, writer);
                break;
            } else if (event instanceof StreamText streamText) {
                printer.onDelta(streamText.text());
            } else if (event instanceof ToolUseEvent toolUse) {
                printer.onToolUse(new ToolUseBlock(toolUse.toolId(), toolUse.toolName(), toolUse.args()));
            } else if (event instanceof ToolResultEvent toolResult) {
                turnResults.add(toolResult.isError()
                        ? ToolResult.failure(toolResult.output())
                        : ToolResult.success(toolResult.output()).withDisplay(toolResult.display()));
                elapsedList.add(toolResult.elapsedMs());
            } else if (event instanceof TurnComplete) {
                printer.updateToolCalls(turnResults, elapsedList);
                printer.finishTurn(); // 本轮文本与卡片转正进回滚，状态复位后同一实例服务下一轮
                turnResults = new ArrayList<>();
                elapsedList = new ArrayList<>();
            } else if (event instanceof UsageEvent usageEvent) {
                Usage usage = usageEvent.usage();
                long out = usage.outputTokens();
                if (out > 0) {
                    totalOutputTokens = out;
                }
                long in = usage.inputTokens();
                if (in > 0) {
                    estimatedInputTokens = in;
                }
                conversation.recordPromptTokens(usage.promptTokens());
            } else if (event instanceof RetryEvent retry) {
                output.appendLine("（重试中：" + retry.reason() + "）");
                live.appendCommitted(writer, "（重试中：" + retry.reason() + "）");
            } else if (event instanceof ErrorEvent error) {
                printer.onError(new ProviderException(error.message()));
            } else if (event instanceof ConfirmationRequestEvent confirm) {
                confirm.response().answer(confirmAnswerer.apply(confirm));
            } else if (event instanceof ChoiceRequestEvent choice) {
                choice.response().answer(choiceAnswerer.apply(choice));
            }

            // 实时计时器：挂成尾行，恒为屏幕最后一行、光标正上方，原地刷新无需任何行数计数
            long now = System.currentTimeMillis();
            if (timer.shouldUpdate(now)) {
                live.setTailRow(writer, timer.text(now));
            }
        }
    }

    /**
     * 估算当前请求的 input tokens：遍历 conversation 历史 + system prompt + 环境消息，
     * 按字符数 ÷ 4 粗估。代理未透传 input_tokens 时的兜底方案。
     */
    private long estimateCurrentInputTokens() {
        long total = 0;
        for (ChatMessage msg : conversation.history()) {
            total += Conversation.estimateTokens(msg);
        }
        return total;
    }

    /** 循环轮数上限：配置缺失时用默认值（与 ConfigValidator 一致） */
    private int maxIterations() {
        Integer configured = config.getMaxIterations();
        return configured != null && configured > 0 ? configured : ConfigValidator.DEFAULT_MAX_ITERATIONS;
    }

    /** 每轮结束输出 usage 脚注：撤销计时尾行后把 usage 行追加在原计时行位置。 */
    private void printUsageFootnote(long inputTokens, long outputTokens, long elapsedMs,
                                    LiveRegionRenderer live, Writer writer) {
        String elapsed = String.format("%.1fs", elapsedMs / 1000.0);
        String line = "usage: in " + inputTokens
                + " · out " + outputTokens
                + "  总耗时" + elapsed;
        live.clearTailRow(writer);
        output.appendLine(line);
        live.appendCommitted(writer, line);
        log.info("{}", line);
    }

    /** 打印分隔线 */
    private void printDivider(LiveRegionRenderer live, Writer writer) {
        int width = renderContext.terminalWidth();
        String line = StatusBar.divider(width);
        output.appendLine(line);
        live.appendCommitted(writer, line);
    }

    /** 渲染等待输入帧：模式提示 + 分隔线（提示符上方）+ 页脚分隔线 + 模型信息（提示符下方）。
     *  光标停在预留的提示符行，由 JLine 绘制 >*；linesSinceFrame 由 renderWaitingFrame 设为 2。 */
    private void renderWaitingFrame(LiveRegionRenderer live, Writer writer) {
        int width = renderContext.terminalWidth();
        PermissionMode current = permissionCheckerSupplier.get().mode();
        String modeHint = StatusBar.modeLine(current.configValue(), StatusBar.nextModeName(current), width);
        String divider = StatusBar.divider(width);
        String model = conversation.getModel();
        double ctxFraction = conversation.contextUsageFraction();
        String footerModel = StatusBar.infoLine(model, ctxFraction, projectRoot.toString(), width);
        output.appendLine(modeHint);
        output.appendLine(divider);
        output.appendLine(divider);
        output.appendLine(footerModel);
        live.renderWaitingFrame(writer, modeHint, divider, divider, footerModel);
    }

    /** 事件轮询：20ms 超时；中断恢复中断位并返回 null */
    private static AgentEvent pollEvent(BlockingQueue<AgentEvent> events) {
        try {
            return events.poll(20, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void awaitLoopEnd(Agent agent) {
        long deadline = System.currentTimeMillis() + awaitLoopEndTimeoutMillis;
        while (agent.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (agent.isRunning()) {
            log.warn("取消后 agent 线程未在 {}ms 内收尾；其残留历史写入将被 epoch 校验忽略",
                    awaitLoopEndTimeoutMillis);
        }
    }

    /** 循环收尾：按终止原因补提示（MAX_ITERATIONS / PLAN_DELIVERED / CANCELED / ERROR） */
    private void completeLoop(Agent agent, LiveRegionRenderer live, Writer writer) {
        switch (agent.termination()) {
            case MAX_ITERATIONS -> {
                output.appendLine("（达到最大轮数，已停止执行）");
                live.appendCommitted(writer, "（达到最大轮数，已停止执行）");
            }
            case PLAN_DELIVERED -> {
                output.appendLine("（计划已交付）");
                live.appendCommitted(writer, "（计划已交付）");
                Path plan = agent.planPath();
                if (plan != null) {
                    try {
                        String content = Files.readString(plan);
                        output.append(content);
                        live.appendCommitted(writer, content);
                    } catch (IOException e) {
                        log.warn("读取计划文件失败：{}", e.getMessage());
                    }
                }
                output.appendLine("输入 /do 退出 plan 模式开始执行");
                live.appendCommitted(writer, "输入 /do 退出 plan 模式开始执行");
            }
            case CANCELED -> {
                output.appendLine("（已中断）");
                live.appendCommitted(writer, "（已中断）");
            }
            case ERROR -> { /* ErrorEvent 已输出错误行，无需重复 */ }
            case NORMAL -> { /* 自然收尾，无提示 */ }
        }
    }
}
