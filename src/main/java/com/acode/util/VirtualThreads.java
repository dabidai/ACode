package com.acode.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用级共享虚拟线程池：统一各任务执行器（工具执行 / 流式 provider / 读组并发）的线程来源。
 *  永不关闭（与应用同生命周期）。
 *  <p>刻意例外（不并入本池，作为约定记录）：
 *  <ul>
 *    <li>acode-agent 顶层虚拟线程（Agent.run）：单线程非池，需命名且取消依赖 thread.interrupt；</li>
 *    <li>BashTool 子进程输出排空 reader 平台线程：不可中断，join(3000) 兜底。</li>
 *  </ul> */
public final class VirtualThreads {

    public static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private VirtualThreads() {
    }
}
