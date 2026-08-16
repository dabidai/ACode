package com.acode.agent;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 AI 选择请求的答复通道：agent 线程 {@link #await} 阻塞等待，UI 主线程拿到
 * ChoiceRequestEvent 后 {@link #answer} 回传选中项（取消回传 null）。
 * await 以 50ms 轮询检测 cancelled，保证取消/退出路径能及时醒来；answer 幂等（容量 1，第二次忽略）。
 * 队列元素用 Optional 承载「取消」语义，规避 LinkedBlockingQueue 的 null 禁令。
 */
public class Choice {

    private final BlockingQueue<Optional<String>> queue = new LinkedBlockingQueue<>(1);

    /** 发布选中项；null 表示取消。重复调用只生效第一次。 */
    public void answer(String selected) {
        queue.offer(Optional.ofNullable(selected));
    }

    /**
     * 阻塞等待选中项；cancelled 置位立即返回 null（等价取消）。中断恢复中断位并返回 null。
     * null 返回值 = 取消。
     */
    public String await(AtomicBoolean cancelled) {
        while (!cancelled.get()) {
            try {
                Optional<String> answer = queue.poll(50, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    return answer.orElse(null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
