package com.acode.ui;

/**
 * 流式响应期间的实时计时器：只负责「该不该刷新」与「刷新成什么文本」，不碰光标。
 * 计时行由 {@link LiveRegionRenderer#setTailRow} 挂成尾行——恒为屏幕最后一行、光标正上方，
 * 所以原地刷新的偏移恒为 1，既不需要跨调用累计「计时行与光标之间隔了几行」（那种计数在
 * TurnComplete 重建 printer、finishTurn 自清零、重试提示与权限弹窗绕过计数时都会漏计），
 * 也不可能因回答超过一屏、计时行滚进回滚缓冲而让上移序列被终端截断、反复覆盖可见区顶行。
 * 轮次结束时由 ExchangeRunner 用 clearTailRow + appendCommitted 把 usage 行落在原计时行位置。
 */
public final class StreamingTimer {

    private static final String STYLE_DIM = "\033[90m";
    private static final String RESET = "\033[0m";
    private static final long UPDATE_INTERVAL_MS = 500;

    private final long startTime;
    private long lastUpdateTime = 0;

    public StreamingTimer(long startTime) {
        this.startTime = startTime;
    }

    /** 是否到了更新时间（500ms 节流）；首次调用必然命中，让尾行立即出现。 */
    public boolean shouldUpdate(long now) {
        if (now - lastUpdateTime < UPDATE_INTERVAL_MS) {
            return false;
        }
        lastUpdateTime = now;
        return true;
    }

    /** 计时行文本：⏱ 已耗时。 */
    public String text(long now) {
        return STYLE_DIM + "  ⏱ " + formatElapsed(now - startTime) + RESET;
    }

    private static String formatElapsed(long ms) {
        if (ms < 60_000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        long mins = ms / 60_000;
        double secs = (ms % 60_000) / 1000.0;
        return String.format("%dm%.1fs", mins, secs);
    }
}
