package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 摘要分区与重建规划：给定完整历史与保留尾预算，从尾部按「完整轮次单元」回溯选出保留尾
 * （预算内），其余为摘要区。重建为「摘要(user) + 边界提醒 + 保留尾」。
 *
 * <p>关键语义（ch07）：<b>不可压缩（逐字保留）仅限未闭环步</b>——最新一条未答复 user 原文 +
 * 最末尚无结果的 assistant tool_use；<b>已闭环轮次</b>（结果已回填、流程已推进）一律属普通历史，
 * 可进保留尾预算或摘要区（单个长请求内连续多轮也可中途压缩）。
 *
 * <p>planner 不写死重建首条角色，只保证不变量：整段角色相邻合法、请求以 user 结尾、
 * 无悬空 tool_use/tool_result、重建后整体估算 ≤ 触发点（后者由执行器按同一口径复核）。
 */
public class CompactionPlanner {

    /** 重建边界提醒（独立 assistant 消息文本；assistant 开头保留尾时并入摘要 user 文本，防同角色相邻） */
    public static final String BOUNDARY_HINT =
            "（以上为压缩后的历史摘要。此处之前的对话内容已被压缩为要点；如需具体文件内容、"
                    + "报错原文或此前代码细节，请用 ReadFile / Grep 等读类工具重新读取，勿凭摘要猜测细节。）";

    /** 分区结果：summaryRegion = history[0..cutIndex)，retainedTail = history[cutIndex..size) */
    public record Partition(int cutIndex, boolean compressible) {
        /** 无可压缩摘要区 */
        static Partition none() {
            return new Partition(0, false);
        }
    }

    private final ContextPolicy policy;

    public CompactionPlanner(ContextPolicy policy) {
        this.policy = policy;
    }

    /**
     * 计算分区。保留尾从尾部装入、按前端删除「完整轮次单元」的方式逼近预算；任何切分都不拆散
     * assistant tool_use 与其后紧邻 tool_result 的配对。不可压缩的未闭环步留在保留尾末尾逐字保留。
     */
    public Partition plan(List<ChatMessage> history) {
        int size = history.size();
        if (size == 0) {
            return Partition.none();
        }
        int protectFrom = protectedFrom(history);
        int maxCut = (protectFrom == size) ? size - 1 : protectFrom;
        int cut = 0;
        while (cut < maxCut && estimateTail(history, cut) > policy.TAIL_BUDGET_TOKENS) {
            cut += unitSize(history, cut, size);
        }
        if (cut > maxCut) {
            cut = maxCut;
        }
        return new Partition(cut, cut > 0);
    }

    /** 摘要区消息（待压缩的旧历史） */
    public List<ChatMessage> summaryRegion(List<ChatMessage> history, Partition plan) {
        return new ArrayList<>(history.subList(0, plan.cutIndex()));
    }

    /** 保留尾消息 */
    public List<ChatMessage> retainedTail(List<ChatMessage> history, Partition plan) {
        return new ArrayList<>(history.subList(plan.cutIndex(), history.size()));
    }

    /**
     * 用生成的摘要文本重建对话：摘要(user) + 边界提醒 + 保留尾。
     * 保留尾以 USER 开头时插独立 assistant 边界提醒；保留尾以 ASSISTANT（工具调用）开头时
     * 把边界提醒并入摘要 user 文本（避免边界 assistant 与保留尾首条 assistant 同角色相邻）。
     * 返回前经 Conversation.sanitize 清洗，确保无孤儿工具块。
     */
    public List<ChatMessage> rebuild(List<ChatMessage> history, Partition plan, String summaryText) {
        List<ChatMessage> tail = retainedTail(history, plan);
        String body = summaryText == null ? "" : summaryText.trim();
        List<ChatMessage> out = new ArrayList<>();
        boolean tailStartsUser = !tail.isEmpty() && tail.get(0).role() == ChatMessage.Role.USER;
        if (tailStartsUser) {
            out.add(ChatMessage.of(ChatMessage.Role.USER, body));
            out.add(ChatMessage.of(ChatMessage.Role.ASSISTANT, BOUNDARY_HINT));
            out.addAll(tail);
        } else {
            out.add(ChatMessage.of(ChatMessage.Role.USER, body + "\n\n" + BOUNDARY_HINT));
            out.addAll(tail);
        }
        return Conversation.sanitize(out);
    }

    /** 保留尾整体估算（token），沿用 Conversation 分块口径 */
    private int estimateTail(List<ChatMessage> history, int from) {
        int sum = 0;
        for (int i = from; i < history.size(); i++) {
            sum += Conversation.estimateTokens(history.get(i));
        }
        return sum;
    }

    /** 前端删除一个"完整轮次单元"占用的消息数：assistant 带 tool_use 且紧邻其后是纯 tool_result user → 2，否则 1 */
    private static int unitSize(List<ChatMessage> history, int at, int size) {
        ChatMessage first = history.get(at);
        if (first.role() == ChatMessage.Role.ASSISTANT && containsToolUse(first)
                && at + 1 < size) {
            ChatMessage next = history.get(at + 1);
            if (next.role() == ChatMessage.Role.USER && isAllToolResults(next)) {
                return 2;
            }
        }
        return 1;
    }

    /**
     * 未闭环步起始下标（该下标及其后逐字保留，不得压进摘要）：
     * <ul>
     *   <li>末尾 user 为纯 tool_result（已闭环工具轮）→ 不保护，返回 size；</li>
     *   <li>末尾 user 为普通文本（最新未答复提问）→ 保护该 user；</li>
     *   <li>末尾 assistant 含尚无结果的 tool_use → 保护该 assistant（正在执行）；</li>
     *   <li>末尾 assistant 纯文本（已闭环回答）→ 不保护。</li>
     * </ul>
     */
    private static int protectedFrom(List<ChatMessage> history) {
        int size = history.size();
        ChatMessage last = history.get(size - 1);
        if (last.role() == ChatMessage.Role.USER) {
            if (isAllToolResults(last)) {
                return size;
            }
            return size - 1; // 最新未答复 user：逐字保留
        }
        // ASSISTANT
        if (containsToolUse(last) && !hasMatchingResult(history, last)) {
            return size - 1; // 最末尚无结果的 tool_use：整条逐字保留
        }
        return size;
    }

    /** 该 assistant 消息里的每个 tool_use 是否都已在历史后续找到对应 tool_result */
    private static boolean hasMatchingResult(List<ChatMessage> history, ChatMessage assistant) {
        Set<String> resultIds = new HashSet<>();
        for (ChatMessage m : history) {
            for (ContentBlock b : m.blocks()) {
                if (b instanceof ToolResultBlock tr) {
                    resultIds.add(tr.toolUseId());
                }
            }
        }
        for (ContentBlock b : assistant.blocks()) {
            if (b instanceof ToolUseBlock tu && !resultIds.contains(tu.id())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsToolUse(ChatMessage message) {
        return message.blocks().stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

    private static boolean isAllToolResults(ChatMessage message) {
        return !message.blocks().isEmpty()
                && message.blocks().stream().allMatch(b -> b instanceof ToolResultBlock);
    }
}
