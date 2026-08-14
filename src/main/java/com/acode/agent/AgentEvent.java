package com.acode.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 与 UI 之间的事件契约：循环过程的所有可观测状态统一作为事件吐出。
 * UI 订阅事件队列渲染，不感知循环内部；Agent 不感知 UI。
 */
public sealed interface AgentEvent {

    /** 事件队列背压上限 */
    int QUEUE_CAPACITY = 64;

    /** 模型文本增量 */
    record StreamText(String text) implements AgentEvent {}

    /** 模型发起工具调用 */
    record ToolUseEvent(String toolId, String toolName, JsonNode args) implements AgentEvent {}

    /** 单个工具执行完成 */
    record ToolResultEvent(String toolId, String toolName, String output, boolean isError) implements AgentEvent {}

    /** 一轮结束（工具结果已回填，可开始下一轮） */
    record TurnComplete(int turn) implements AgentEvent {}

    /** 循环结束（正常/触顶/计划交付/错误统一以此事件收尾，具体原因经 Agent 查询） */
    record LoopComplete(int totalTurns) implements AgentEvent {}

    /** 不可恢复错误 */
    record ErrorEvent(String message) implements AgentEvent {}

    /** 重试预告（UI 显示等待状态） */
    record RetryEvent(String reason, long waitMs) implements AgentEvent {}
}
