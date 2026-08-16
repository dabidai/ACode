package com.acode.agent;

import com.acode.provider.ToolUseBlock;
import com.acode.tool.ToolResult;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交互式工具：执行期间与用户交互（经 events 发布交互事件并阻塞等待 UI 主线程应答），
 * 不走 ToolExecutor 的定时执行路径。实现类实现 {@link #executeInteractive}；生产路径只走该方法。
 * 放 agent 包（与 ConfirmationGate 同层），避免 tool → agent 包依赖循环。
 */
public interface InteractiveTool {

    /**
     * 交互式执行：经 events 发布交互请求事件，阻塞等待用户应答并返回工具结果；
     * cancelled 置位时返回（调用方以 fillCancelled 兜底「已取消」）。
     */
    ToolResult executeInteractive(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled);
}
