package com.acode.agent;

import com.acode.provider.Usage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Agent 与 UI 之间的事件契约：循环过程的所有可观测状态统一作为事件吐出。
 * UI 订阅事件队列渲染，不感知循环内部；Agent 不感知 UI。
 */
public sealed interface AgentEvent {

    /** 事件队列背压上限 */
    int QUEUE_CAPACITY = 64;

    /**
     * 阻塞入队：队满时等待消费者腾位（背压），不丢事件。
     * 中断时恢复中断位，让取消/退出路径能干净收尾。
     */
    static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 模型文本增量 */
    record StreamText(String text) implements AgentEvent {}

    /** 模型发起工具调用 */
    record ToolUseEvent(String toolId, String toolName, JsonNode args) implements AgentEvent {}

    /** 单个工具执行完成（elapsedMs 为工具实际执行耗时毫秒，确认拒绝/取消路径记 0；
     * display 为仅供界面展示的正文，普通/交互路径随结果透传，拒绝路径为 null） */
    record ToolResultEvent(String toolId, String toolName, String output, boolean isError, long elapsedMs, String display)
            implements AgentEvent {}

    /** 一轮结束（工具结果已回填，可开始下一轮） */
    record TurnComplete(int turn) implements AgentEvent {}

    /** 本轮 token 用量（含缓存命中字段；流结束前触发） */
    record UsageEvent(Usage usage) implements AgentEvent {}

    /** 循环结束（正常/触顶/计划交付/错误统一以此事件收尾，具体原因经 Agent 查询） */
    record LoopComplete(int totalTurns) implements AgentEvent {}

    /** 不可恢复错误 */
    record ErrorEvent(String message) implements AgentEvent {}

    /** 重试预告（UI 显示等待状态） */
    record RetryEvent(String reason, long waitMs) implements AgentEvent {}

    /** 工具确认请求：UI 弹 y/n 后经 response 答复；agent 线程在 await 阻塞等待 */
    record ConfirmationRequestEvent(String toolId, String toolName, String argsSummary, Confirmation response)
            implements AgentEvent {}

    /** AI 选择请求：UI 弹多选项菜单，选中项经 response 回传；agent 线程在 await 阻塞等待 */
    record ChoiceRequestEvent(String toolId, String toolName, String question, List<String> options, Choice response)
            implements AgentEvent {}
}
