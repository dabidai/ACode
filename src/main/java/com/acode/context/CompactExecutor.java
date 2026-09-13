package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.ProviderException;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要执行器：把「摘要请求 → 两阶段解析 → 重建 → 熔断/降级」串成一个可复用执行器，并提供自动触发判断。
 * <ul>
 *   <li>摘要请求不携带工具、thinking 关闭、max_tokens 用独立摘要常量；直接经 ChatRequest.builder
 *       构造，不经 Conversation.buildRequest（避免被注入轮次级提醒）；</li>
 *   <li>摘要请求自身超长 → 按「丢最旧分组」重试（次数与比例见 ContextPolicy）；</li>
 *   <li>连续失败达阈值 → 熔断（此后 needsAutoCompact 恒 false），任一次成功压缩或 clear 复位；</li>
 *   <li>自动触发守卫：仅当存在非空摘要区且「压后整体估算上限 < 触发点」才自动触发（防空跑）。</li>
 * </ul>
 */
public class CompactExecutor {

    /** 压缩执行结果 */
    public record Result(boolean changed, boolean failed, String reason,
                         int beforeEstimate, int afterEstimate) {
        static Result success(int before, int after) {
            return new Result(true, false, null, before, after);
        }

        static Result noChange(int estimate) {
            return new Result(false, false, null, estimate, estimate);
        }

        static Result failure(String reason, int estimate) {
            return new Result(false, true, reason, estimate, estimate);
        }

        public boolean noChange() {
            return !changed && !failed;
        }
    }

    private final ChatProvider provider;
    private final Conversation conversation;
    private final ContextPolicy policy;
    private final CompactionPlanner planner;

    /** 连续失败计数：达到 ContextPolicy.BREAKER_LIMIT 后熔断（只罩自动触发） */
    private int consecutiveFailures;

    public CompactExecutor(ChatProvider provider, Conversation conversation, ContextPolicy policy) {
        this.provider = provider;
        this.conversation = conversation;
        this.policy = policy;
        this.planner = new CompactionPlanner(policy);
    }

    /**
     * 自动触发判断：整体估算 ≥ 触发点，且存在早于保留尾的可压缩摘要区，
     * 且压后整体估算上限 < 触发点（守卫：避免"压了也压不低"时每轮空跑一次摘要调用）。
     */
    public boolean needsAutoCompact() {
        if (conversation.messageCount() == 0) {
            return false;
        }
        if (isBreakerTripped()) {
            return false;
        }
        int estimate = conversation.estimateContextTokens();
        int trigger = ContextPolicy.triggerPointFor(conversation.maxContextTokens());
        if (estimate < trigger) {
            return false;
        }
        CompactionPlanner.Partition plan = planner.plan(conversation.history());
        if (!plan.compressible()) {
            return false;
        }
        return predictedPostEstimate(plan);
    }

    /**
     * 执行压缩。manual=true 时无条件压缩（无可压缩摘要区返回 noChange）；
     * manual=false 仅当 needsAutoCompact 为真才有意义（调用方已判守卫）。
     */
    public Result run(boolean manual) {
        return run(manual, null);
    }

    /** 带保留重点的压缩：focus 非空时追加进摘要指令末尾（空则行为与 run(manual) 一致） */
    public Result run(boolean manual, String focus) {
        List<ChatMessage> history = conversation.history();
        CompactionPlanner.Partition plan = planner.plan(history);
        int before = conversation.estimateContextTokens();
        if (!plan.compressible()) {
            return Result.noChange(before);
        }
        List<ChatMessage> region = planner.summaryRegion(history, plan);
        String summary = requestSummary(region, focus);
        if (summary == null) {
            consecutiveFailures++;
            return Result.failure("摘要生成失败", before);
        }
        List<ChatMessage> rebuilt = planner.rebuild(history, plan, summary);
        conversation.replaceAll(rebuilt);
        consecutiveFailures = 0;
        int after = conversation.estimateContextTokens();
        return Result.success(before, after);
    }

    /** 熔断已触发？ */
    public boolean isBreakerTripped() {
        return consecutiveFailures >= policy.BREAKER_LIMIT;
    }

    /** clear 钩子 / 压缩成功后复位连续失败计数 */
    public void resetBreaker() {
        consecutiveFailures = 0;
    }

    /** 预测压缩后整体估算上限：系统/环境 + 摘要上限 + 保留尾 + 边界提醒。返回 true 表示 < 触发点 */
    private boolean predictedPostEstimate(CompactionPlanner.Partition plan) {
        int sysEnv = conversation.estimateContextTokens() - estimateHistory(conversation.history());
        int tail = estimateHistory(planner.retainedTail(conversation.history(), plan));
        int boundary = Conversation.estimateTokens(CompactionPlanner.BOUNDARY_HINT);
        int upper = sysEnv + policy.SUMMARY_MAX_TOKENS + tail + boundary + 4;
        int trigger = ContextPolicy.triggerPointFor(conversation.maxContextTokens());
        return upper < trigger;
    }

    private static int estimateHistory(List<ChatMessage> history) {
        int sum = 0;
        for (ChatMessage m : history) {
            sum += Conversation.estimateTokens(m);
        }
        return sum;
    }

    /**
     * 生成摘要正文：发一次不携带工具、thinking 关闭、max_tokens 独立给足的摘要请求并解析两阶段输出。
     * 摘要请求自身"上下文超长" → 按丢最旧分组重试；仍失败返回 null（调用方计熔断）。
     */
    private String requestSummary(List<ChatMessage> region, String focus) {
        List<ChatMessage> working = new ArrayList<>(region);
        int overlongDrops = 0;
        boolean ratioDropped = false;
        while (!working.isEmpty()) {
            SummaryCollector collector = new SummaryCollector();
            provider.streamChat(summaryRequest(working, focus), collector);
            if (collector.error == null) {
                String summary = parseSummary(collector.text.toString());
                return summary == null || summary.isEmpty() ? null : summary;
            }
            if (!ContextTooLong.matches(collector.error)) {
                return null; // 非"超长"的硬失败
            }
            if (overlongDrops < policy.OVERLONG_DROP_RETRIES) {
                dropOldestGroup(working);
                overlongDrops++;
            } else if (!ratioDropped) {
                dropRatioGroups(working);
                ratioDropped = true;
            } else {
                return null; // 重试与降级都耗尽仍超长
            }
        }
        return null;
    }

    /** 组装摘要请求：SYSTEM 指令首条 + 摘要区消息；不设 tools、thinking 关闭、max_tokens 用独立摘要常量 */
    private ChatRequest summaryRequest(List<ChatMessage> region, String focus) {
        List<ChatMessage> messages = new ArrayList<>(region.size() + 1);
        messages.add(ChatMessage.of(ChatMessage.Role.SYSTEM, SummaryPrompt.instruction(focus)));
        messages.addAll(region);
        return ChatRequest.builder()
                .model(conversation.model())
                .thinking(false)
                .maxTokens(policy.SUMMARY_MAX_TOKENS)
                .messages(messages)
                .build();
    }

    /** 丢最旧一个"完整轮次单元"（assistant tool_use 与其 tool_result 不拆散），保证余下区段无孤儿工具块 */
    private static void dropOldestGroup(List<ChatMessage> working) {
        if (working.isEmpty()) {
            return;
        }
        int unit = unitSize(working, 0);
        for (int i = 0; i < unit && !working.isEmpty(); i++) {
            working.remove(0);
        }
    }

    /** 丢弃约 ratio 比例的"消息组"（整组为完整轮次单元），用于降级重试 */
    private static void dropRatioGroups(List<ChatMessage> working) {
        if (working.isEmpty()) {
            return;
        }
        int groups = countGroups(working);
        int toDrop = Math.max(1, (int) Math.ceil(groups * ContextPolicy.OVERLONG_DROP_RATIO));
        for (int i = 0; i < toDrop && !working.isEmpty(); i++) {
            dropOldestGroup(working);
        }
    }

    /** 数出"完整轮次单元"组数（与 dropOldestGroup 的成对口径一致） */
    private static int countGroups(List<ChatMessage> list) {
        int count = 0;
        int i = 0;
        while (i < list.size()) {
            i += unitSize(list, i);
            count++;
        }
        return count;
    }

    /** 前端一个单元占几条：assistant 带 tool_use 且紧邻其后为纯 tool_result user → 2，否则 1 */
    private static int unitSize(List<ChatMessage> list, int at) {
        ChatMessage m = list.get(at);
        if (m.role() == ChatMessage.Role.ASSISTANT && containsToolUse(m) && at + 1 < list.size()) {
            ChatMessage next = list.get(at + 1);
            if (next.role() == ChatMessage.Role.USER && !next.blocks().isEmpty()
                    && next.blocks().stream().allMatch(b -> b instanceof com.acode.provider.ToolResultBlock)) {
                return 2;
            }
        }
        return 1;
    }

    private static boolean containsToolUse(ChatMessage message) {
        return message.blocks().stream().anyMatch(b -> b instanceof com.acode.provider.ToolUseBlock);
    }

    /** 从文本解析最终摘要：先丢弃 <analysis> 草稿区；有 <summary> 标签取其中正文，否则整段兜底 */
    static String parseSummary(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripSections(raw, SummaryPrompt.ANALYSIS_OPEN, SummaryPrompt.ANALYSIS_CLOSE);
        int s = text.indexOf(SummaryPrompt.SUMMARY_OPEN);
        int e = text.lastIndexOf(SummaryPrompt.SUMMARY_CLOSE);
        if (s >= 0 && e > s) {
            text = text.substring(s + SummaryPrompt.SUMMARY_OPEN.length(), e);
        }
        return text.trim();
    }

    /** 去掉所有 open…close 包裹的区段（两阶段草稿丢弃用） */
    private static String stripSections(String text, String open, String close) {
        StringBuilder sb = new StringBuilder(text);
        int idx;
        while ((idx = sb.indexOf(open)) >= 0) {
            int end = sb.indexOf(close, idx + open.length());
            if (end < 0) {
                sb.delete(idx, sb.length());
                break;
            }
            sb.delete(idx, end + close.length());
        }
        return sb.toString();
    }

    /** 同步收集摘要流文本（正常对话只发文本增量，无工具/用量需要） */
    private static final class SummaryCollector implements ChatListener {
        final StringBuilder text = new StringBuilder();
        ProviderException error;

        @Override
        public void onDelta(String delta) {
            text.append(delta);
        }

        @Override
        public void onError(ProviderException e) {
            this.error = e;
        }
    }
}
