package com.acode.mcp;

import java.util.function.Consumer;

/**
 * MCP 传输抽象：消息以换行分隔 JSON 帧在双方间传送。
 * 实现负责底层通道（stdio 子进程管道 / HTTP POST），协议层经 {@link #send}
 * 发出消息、经 {@link #setMessageHandler} 接收远端消息。
 */
public interface Transport extends AutoCloseable {

    /** 打开底层通道（幂等）。未调用前 send/close 应安全失败。 */
    void start();

    /** 发送一条消息；通道已关闭或已死亡时抛连接失败异常。 */
    void send(JsonRpcMessage message);

    /** 注册消息接收回调：远端每来一条消息调用一次。 */
    void setMessageHandler(Consumer<JsonRpcMessage> handler);

    /** 注册传输终止回调：EOF / 进程退出 / 流关闭时恰好调用一次，供协议层完成挂起请求。 */
    default void setTerminationHandler(Runnable handler) {
    }

    /** 底层通道当前是否存活（进程活着 / 最近请求成功） */
    boolean isAlive();

    /** 关闭通道：先优雅关闭、再强制兜底；幂等。 */
    @Override
    void close();
}
