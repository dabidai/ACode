package com.acode.context;

/**
 * 上下文管理预算常量与按窗口计算的触发点（值统一见 checklist.md 顶部「默认值说明」）。
 * 固定常量、不随窗口比例浮动：保护的是"单轮波动"，与窗口大小无关。
 */
public final class ContextPolicy {

    /** 包内/测试用实例（常量均为静态，实例仅为装配参数形态一致） */
    ContextPolicy() {
    }

    /** 单工具结果可见保留上限（字符）：正文恰好等于该值全文保留；超过才落盘替换 */
    public static final int SINGLE_RESULT_KEEP_LIMIT_CHARS = 50_000;

    /** 同批聚合上限（单条 tool_result user 消息合计，字符）：批内从未落盘的最大者开始替换 */
    public static final int BATCH_AGGREGATE_LIMIT_CHARS = 200_000;

    /** 落盘预览正文长度（字符）：取正文前若干字符 */
    public static final int PREVIEW_LENGTH_CHARS = 2_048;

    /** 摘要输出预留（token）：既作触发点预留，也作摘要请求 max_tokens 的独立常量 */
    public static final int SUMMARY_OUTPUT_RESERVE_TOKENS = 20_000;

    /** 摘要请求级 max_tokens 独立常量（非对话级 8_192，防超长摘要被截断） */
    public static final int SUMMARY_MAX_TOKENS = SUMMARY_OUTPUT_RESERVE_TOKENS;

    /** 自动压缩安全余量（token） */
    public static final int AUTO_COMPACT_SAFETY_MARGIN_TOKENS = 13_000;

    /** 保留尾预算（token）：按完整轮次从尾部装入 */
    public static final int TAIL_BUDGET_TOKENS = 8_000;

    /** 摘要连续失败熔断阈值：达到后本会话不再自动触发，直到任一次成功压缩或 clear 重置 */
    public static final int BREAKER_LIMIT = 3;

    /** 摘要请求自身"上下文超长"时，丢最旧分组的重试次数 */
    public static final int OVERLONG_DROP_RETRIES = 3;

    /** 丢最旧分组仍失败时，再丢弃剩余消息组的比例后重试一次 */
    public static final double OVERLONG_DROP_RATIO = 0.20;

    /** 自动压缩触发点 = 窗口 − 摘要输出预留 − 安全余量（默认 200_000 → 167_000） */
    public static int triggerPointFor(int maxContextTokens) {
        return maxContextTokens - SUMMARY_OUTPUT_RESERVE_TOKENS - AUTO_COMPACT_SAFETY_MARGIN_TOKENS;
    }
}
