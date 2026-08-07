package com.acode.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 测试桩：模拟流式成功（分批 delta）或失败（抛指定错误）。
 * 录制收到的回调，供上层单测断言。
 */
public class FakeProvider implements ChatProvider {

    private final List<String> chunks;
    private final ProviderException error;
    private final List<String> recordedDeltas = new ArrayList<>();
    private boolean completed;
    private ProviderException receivedError;

    private FakeProvider(List<String> chunks, ProviderException error) {
        this.chunks = chunks;
        this.error = error;
    }

    /** 正常流：按 chunks 顺序发 delta，最后 onComplete */
    public static FakeProvider streaming(String... chunks) {
        return new FakeProvider(List.of(chunks), null);
    }

    /** 失败流：直接 onError，不发任何 delta */
    public static FakeProvider failing(ProviderException error) {
        return new FakeProvider(List.of(), error);
    }

    @Override
    public void streamChat(ChatRequest request, ChatListener listener) {
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
}
