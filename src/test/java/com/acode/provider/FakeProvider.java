package com.acode.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 测试桩：模拟流式成功（分批 delta）、失败（抛指定错误），
 * 或按脚本逐次调用驱动多轮工具闭环（tool_use → 回传 → 最终文本）。
 * 录制收到的回调与每次请求，供上层单测断言。
 */
public class FakeProvider implements ChatProvider {

    /** 一次监听器动作：脚本的最小单元 */
    @FunctionalInterface
    public interface Action {
        void run(ChatListener listener);
    }

    public static Action delta(String text) {
        return listener -> listener.onDelta(text);
    }

    public static Action toolUse(String id, String name, JsonNode input) {
        return listener -> listener.onToolUse(new ToolUseBlock(id, name, input));
    }

    public static Action complete() {
        return ChatListener::onComplete;
    }

    /** 带流结束原因的正常结束（模拟 stop_reason / finish_reason 透传） */
    public static Action complete(String stopReason) {
        return listener -> listener.onComplete(stopReason);
    }

    public static Action error(ProviderException e) {
        return listener -> listener.onError(e);
    }

    /** 上报本轮 token 用量 */
    public static Action usage(Usage usage) {
        return listener -> listener.onUsage(usage);
    }

    private final List<List<Action>> scripts;
    private final List<String> chunks;
    private final ProviderException error;
    private final List<String> recordedDeltas = new ArrayList<>();
    private final List<ChatRequest> receivedRequests = new ArrayList<>();
    private boolean completed;
    private ProviderException receivedError;
    private int callIndex;

    private FakeProvider(List<List<Action>> scripts, List<String> chunks, ProviderException error) {
        this.scripts = scripts;
        this.chunks = chunks;
        this.error = error;
    }

    /** 正常流：按 chunks 顺序发 delta，最后 onComplete */
    public static FakeProvider streaming(String... chunks) {
        return new FakeProvider(null, List.of(chunks), null);
    }

    /** 失败流：直接 onError，不发任何 delta */
    public static FakeProvider failing(ProviderException error) {
        return new FakeProvider(null, List.of(), error);
    }

    /** 脚本流：每次 streamChat 依次消费一份脚本（用于模拟多轮工具调用） */
    public static FakeProvider scripted(List<List<Action>> scripts) {
        return new FakeProvider(scripts, List.of(), null);
    }

    @Override
    public void streamChat(ChatRequest request, ChatListener listener) {
        receivedRequests.add(request);
        if (scripts != null) {
            if (callIndex >= scripts.size()) {
                return; // 脚本耗尽，静默结束（不回调）
            }
            for (Action action : scripts.get(callIndex++)) {
                action.run(listener);
            }
            return;
        }
        if (error != null) {
            receivedError = error;
            listener.onError(error);
            return;
        }
        for (String chunk : chunks) {
            recordedDeltas.add(chunk);
            listener.onDelta(chunk);
        }
        completed = true;
        listener.onComplete();
    }

    public List<String> recordedDeltas() {
        return recordedDeltas;
    }

    public boolean isCompleted() {
        return completed;
    }

    public ProviderException receivedError() {
        return receivedError;
    }

    /** 依次收到的请求（第 i 轮对应第 i 个） */
    public List<ChatRequest> receivedRequests() {
        return receivedRequests;
    }
}
