package com.acode.agent;

import com.acode.provider.ToolUseBlock;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具确认门槛：执行器在跑非 READ 工具前调用，返回 true 放行、false 拒绝。
 * 事件握手实现见 {@link EventConfirmationGate}；默认 {@link #ALWAYS_ALLOW} 用于
 * 存量测试与未装配 gate 的调用方。
 */
@FunctionalInterface
public interface ConfirmationGate {

    boolean confirm(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled);

    /** 默认放行：不拦截任何工具（存量测试零改动）。 */
    ConfirmationGate ALWAYS_ALLOW = (call, events, cancelled) -> true;
}
