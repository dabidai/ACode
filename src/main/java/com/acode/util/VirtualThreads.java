package com.acode.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用级共享虚拟线程池：供流式 provider 与后台任务使用。
 *  永不关闭（与应用同生命周期）。
 *  <p>刻意例外（不并入本池，作为约定记录）：
 *  <ul>
 *    <li>acode-agent 顶层虚拟线程（Agent.run）：单线程非池，取消时等待其退出；</li>
 *    <li>工具与并发读组的虚拟线程：显式持有 Thread，取消时必须等待实际退出；</li>
 *    <li>BashTool 子进程输出排空 reader 平台线程：不可中断，join(3000) 兜底。</li>
 *  </ul> */
public final class VirtualThreads {

    public static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private VirtualThreads() {
    }
}
