package com.acode.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次确认请求的答复通道：agent 线程 {@link #await} 阻塞等待，UI 主线程拿到
 * ConfirmationRequestEvent 后 {@link #answer} 回传结果。await 以 50ms 轮询检测
 * cancelled，保证取消/退出路径能及时醒来；answer 幂等（容量 1，第二次忽略）。
 */
public class Confirmation {

    private final BlockingQueue<Boolean> queue = new LinkedBlockingQueue<>(1);

    /** 发布答复。重复调用只生效第一次。 */
    public void answer(boolean approved) {
        queue.offer(approved);
    }

    /**
     * 阻塞等待答复；cancelled 置位立即返回 false（等价拒绝）。中断恢复中断位并返回 false。
     */
    public boolean await(AtomicBoolean cancelled) {
        while (!cancelled.get()) {
            try {
                Boolean answer = queue.poll(50, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    return answer;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
