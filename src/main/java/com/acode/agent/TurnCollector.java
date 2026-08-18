package com.acode.agent;

import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.UsageEvent;
import com.acode.provider.ChatListener;
import com.acode.provider.ProviderException;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮流式响应的收集器：累积文本 / 工具调用 / 结束原因，同时把文本增量与
 * 工具调用转发进事件队列。cancelled 置位后忽略一切回调（守卫模式）。
 */
public class TurnCollector implements ChatListener {

    private final BlockingQueue<AgentEvent> events;
    private final AtomicBoolean cancelled;
    private final StringBuilder text = new StringBuilder();
    private final List<ToolUseBlock> toolUses = new ArrayList<>();
    private String stopReason;
    private ProviderException error;
    private Usage usage;

    public TurnCollector(BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
        this.events = events;
        this.cancelled = cancelled;
    }

    @Override
    public void onUsage(Usage usage) {
        if (cancelled.get()) {
            return;
        }
        this.usage = usage;
        AgentEvent.putSafe(events, new UsageEvent(usage));
    }

    @Override
    public void onDelta(String delta) {
        if (cancelled.get()) {
            return;
        }
        text.append(delta);
        AgentEvent.putSafe(events, new StreamText(delta));
    }

    @Override
    public void onToolUse(ToolUseBlock toolUse) {
        if (cancelled.get()) {
            return;
        }
        toolUses.add(toolUse);
        AgentEvent.putSafe(events, new ToolUseEvent(toolUse.id(), toolUse.name(), toolUse.input()));
    }

    @Override
    public void onComplete(String stopReason) {
        if (cancelled.get()) {
            return;
        }
        this.stopReason = stopReason;
    }

    @Override
    public void onError(ProviderException error) {
        if (cancelled.get()) {
            return;
        }
        this.error = error;
    }

    /** 本轮累积的完整文本 */
    public String text() {
        return text.toString();
    }

    /** 本轮模型发起的工具调用，按声明顺序 */
    public List<ToolUseBlock> toolUses() {
        return List.copyOf(toolUses);
    }

    /** 流结束原因（Anthropic stop_reason / OpenAI finish_reason），无则 null */
    public String stopReason() {
        return stopReason;
    }

    /** 本轮记录的流错误；无则 null */
    public ProviderException error() {
        return error;
    }

    /** 本轮 token 用量；解析器未上报时为 null */
    public Usage usage() {
        return usage;
    }
}
