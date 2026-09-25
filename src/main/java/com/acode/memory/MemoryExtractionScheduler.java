package com.acode.memory;

import com.acode.util.VirtualThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 提取调度：同一时刻最多一个提取任务，已有任务在跑时新的触发直接跳过本轮（不排队、不累计）。
 *
 * <p>任务启动前记下会话代次，写回前校验——{@link #reset()}（由 /clear、加载会话联动）
 * 会递增代次，使 in-flight 的那一轮结果整体丢弃、零写入。
 */
public class MemoryExtractionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionScheduler.class);
    private static final long AWAIT_IDLE_SLICE_MS = 5;

    private final MemoryExtractor extractor;
    /** 代次检查、写回与 reset 的共同边界：reset 返回后旧任务不会再写入。 */
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicReference<MemoryExtractor.Outcome> lastOutcome = new AtomicReference<>();

    public MemoryExtractionScheduler(MemoryExtractor extractor) {
        this.extractor = extractor;
    }

    /** 后台异步跑一轮；已有任务在跑则本轮跳过 */
    public void triggerAsync() {
        long gen;
        synchronized (lifecycleLock) {
            if (running.get()) {
                return;
            }
            gen = generation.get();
            running.set(true);
        }
        VirtualThreads.POOL.submit(() -> {
            try {
                Optional<MemoryExtractor.Parsed> operations = extractor.extract();
                synchronized (lifecycleLock) {
                    if (generation.get() != gen) {
                        return; // 代次已失效（clear/加载过）：整轮丢弃，不写任何文件
                    }
                    lastOutcome.set(operations.isEmpty()
                            ? MemoryExtractor.Outcome.empty() : extractor.apply(operations.get()));
                }
            } catch (RuntimeException e) {
                // 提取失败只记日志：不向用户报错、不影响后续对话、不设熔断
                log.warn("记忆提取失败：{}", e.getMessage());
            } finally {
                synchronized (lifecycleLock) {
                    if (generation.get() == gen) {
                        running.set(false);
                    }
                }
                completed.incrementAndGet();
            }
        });
    }

    /** 同步跑一轮（/memory run 手动入口） */
    public MemoryExtractor.Outcome runNow() {
        try {
            return extractor.run();
        } catch (RuntimeException e) {
            log.warn("手动记忆提取失败：{}", e.getMessage());
            return MemoryExtractor.Outcome.failure();
        }
    }

    /** 作废进行中的结果并清运行态：/clear、加载会话等历史重置点联动 */
    public void reset() {
        synchronized (lifecycleLock) {
            generation.incrementAndGet();
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 已收尾的异步轮次（含解析失败） */
    public int completedRuns() {
        return completed.get();
    }

    public int extractorCallCount() {
        return extractor.callCount();
    }

    public MemoryExtractor.Outcome lastOutcome() {
        return lastOutcome.get();
    }

    /** 测试用：等异步轮次收尾 */
    boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(AWAIT_IDLE_SLICE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isRunning();
    }
}
