package com.acode.agent;

import com.acode.agent.AgentEvent.ConfirmationRequestEvent;
import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件握手式确认门槛：先把 ConfirmationRequestEvent 入队（答复通道随事件携带），
 * 再阻塞等待 UI 主线程应答。agent 线程在 await 期间可被取消；队列满时 putSafe
 * 背压阻塞，由主线程消费腾位后放行，不会死锁。
 */
public final class EventConfirmationGate implements ConfirmationGate {

    static final int SUMMARY_MAX_CHARS = 160;

    @Override
    public boolean confirm(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
        Confirmation response = new Confirmation();
        AgentEvent.putSafe(events,
                new ConfirmationRequestEvent(call.id(), call.name(), summarize(call.input()), response));
        return response.await(cancelled);
    }

    /** 调用参数预览：JSON 文本超长截断，供 UI 提示展示。 */
    static String summarize(JsonNode input) {
        if (input == null || input.isNull()) {
            return "";
        }
        String s = input.toString();
        return s.length() <= SUMMARY_MAX_CHARS
                ? s
                : s.substring(0, SUMMARY_MAX_CHARS) + "…";
    }
}
