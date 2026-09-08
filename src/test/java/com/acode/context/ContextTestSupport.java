package com.acode.context;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.LoopComplete;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** 上下文管理集成测试共用的 Agent 收尾等待工具。 */
final class ContextTestSupport {

    private ContextTestSupport() {
    }

    /** 收集事件直到 LoopComplete；超时抛错 */
    static List<AgentEvent> untilLoop(BlockingQueue<AgentEvent> queue, long timeoutMs) throws Exception {
        List<AgentEvent> list = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            list.add(event);
            if (event instanceof LoopComplete) {
                return list;
            }
        }
        throw new AssertionError("未收到 LoopComplete，已收集：" + list.size() + " 个事件");
    }

    /** 等循环线程真正结束（自然收尾后 running 复位） */
    static void awaitNotRunning(Agent agent, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && agent.isRunning()) {
            Thread.sleep(20);
        }
        if (agent.isRunning()) {
            throw new AssertionError("agent 线程未在期限内收尾");
        }
    }
}
