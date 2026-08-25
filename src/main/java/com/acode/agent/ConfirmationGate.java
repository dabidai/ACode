package com.acode.agent;

import com.acode.permission.PermissionResponse;
import com.acode.provider.ToolUseBlock;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具确认门槛：执行器在跑非放行工具前调用，返回三选一 {@link PermissionResponse}。
 * 事件握手实现见 {@link EventConfirmationGate}；默认 {@link #ALWAYS_ALLOW} 用于
 * 存量测试与未装配 gate 的调用方（一律放行）。
 */
@FunctionalInterface
public interface ConfirmationGate {

    PermissionResponse confirm(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled);

    /** 默认放行：不拦截任何工具（存量测试零改动）。 */
    ConfirmationGate ALWAYS_ALLOW = (call, events, cancelled) -> PermissionResponse.ALLOW;
}
