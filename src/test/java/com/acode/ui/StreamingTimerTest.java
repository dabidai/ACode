package com.acode.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计时器只产出「该不该刷新」与「刷新成什么文本」，光标数学全部由尾行协议负责。 */
class StreamingTimerTest {

    /** 真实场景的时间基准是墙钟毫秒（远大于 500），用它避免节流的初始态被 0 基准掩盖。 */
    private static final long BASE = 1_700_000_000_000L;

    @Test
    void firstTickHitsImmediatelySoTailRowAppearsAtOnce() {
        StreamingTimer timer = new StreamingTimer(BASE);
        assertTrue(timer.shouldUpdate(BASE), "首次 tick 必须命中，计时尾行才能立刻出现");
    }

    @Test
    void ticksInsideIntervalAreThrottled() {
        StreamingTimer timer = new StreamingTimer(BASE);
        assertTrue(timer.shouldUpdate(BASE));
        assertTrue(!timer.shouldUpdate(BASE + 1), "1ms 后不应刷新");
        assertTrue(!timer.shouldUpdate(BASE + 499), "间隔不足 500ms 不应刷新");
    }

    @Test
    void tickAtIntervalHitsAgainAndRearmsThrottle() {
        StreamingTimer timer = new StreamingTimer(BASE);
        assertTrue(timer.shouldUpdate(BASE));
        assertTrue(timer.shouldUpdate(BASE + 500), "满 500ms 应再次刷新");
        assertTrue(!timer.shouldUpdate(BASE + 999), "刷新后节流窗口重新起算");
        assertTrue(timer.shouldUpdate(BASE + 1000));
    }

    @Test
    void textShowsTenthsOfASecondBelowOneMinute() {
        StreamingTimer timer = new StreamingTimer(BASE);
        assertEquals("\033[90m  ⏱ " + String.format("%.1fs", 1.2) + "\033[0m",
                timer.text(BASE + 1200));
    }

    @Test
    void textSwitchesToMinutesAtOneMinute() {
        StreamingTimer timer = new StreamingTimer(BASE);
        assertEquals("\033[90m  ⏱ " + String.format("%dm%.1fs", 1L, 5.0) + "\033[0m",
                timer.text(BASE + 65_000), "65s 应显示 1m5.0s");
        assertEquals("\033[90m  ⏱ " + String.format("%dm%.1fs", 2L, 0.0) + "\033[0m",
                timer.text(BASE + 120_000));
    }
}
