package com.acode.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ch07 T1：预算常量与触发点计算与 checklist「默认值说明」逐项一致。 */
class ContextPolicyTest {

    @Test
    void constantsMatchChecklistDefaults() {
        assertEquals(50_000, ContextPolicy.SINGLE_RESULT_KEEP_LIMIT_CHARS);
        assertEquals(200_000, ContextPolicy.BATCH_AGGREGATE_LIMIT_CHARS);
        assertEquals(2_048, ContextPolicy.PREVIEW_LENGTH_CHARS);
        assertEquals(20_000, ContextPolicy.SUMMARY_OUTPUT_RESERVE_TOKENS);
        assertEquals(20_000, ContextPolicy.SUMMARY_MAX_TOKENS);
        assertEquals(13_000, ContextPolicy.AUTO_COMPACT_SAFETY_MARGIN_TOKENS);
        assertEquals(8_000, ContextPolicy.TAIL_BUDGET_TOKENS);
        assertEquals(3, ContextPolicy.BREAKER_LIMIT);
    }

    @Test
    void triggerPointDefaultWindowIs167k() {
        assertEquals(167_000, ContextPolicy.triggerPointFor(200_000),
                "默认 200_000 窗口 → 触发点 167_000");
    }

    @Test
    void triggerPointSmallWindowIs95k() {
        assertEquals(95_000, ContextPolicy.triggerPointFor(128_000),
                "128_000 小窗口 → 触发点 95_000");
    }
}
