package com.acode.provider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证统一接口与回调约定：
 * 成功时 onDelta×N + onComplete 且无 onError；失败时仅 onError。
 */
class FakeProviderTest {

    private final ChatRequest request = ChatRequest.builder()
            .model("test-model")
            .message(ChatMessage.of(ChatMessage.Role.USER, "你好"))
            .build();

    @Test
    void 成功流回调delta和complete() {
        FakeProvider provider = FakeProvider.streaming("你", "好", "世界");
        List<String> deltas = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<ProviderException> error = new AtomicReference<>();

        provider.streamChat(request, new ChatListener() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(ProviderException e) {
                error.set(e);
            }
        });

        assertEquals(List.of("你", "好", "世界"), deltas);
        assertTrue(completed.get());
        assertNull(error.get());
        assertEquals(List.of("你", "好", "世界"), provider.recordedDeltas());
        assertTrue(provider.isCompleted());
    }

    @Test
    void 失败流仅回调error() {
        ProviderException expected = new RateLimitException("限流了");
        FakeProvider provider = FakeProvider.failing(expected);
        List<String> deltas = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<ProviderException> error = new AtomicReference<>();

        provider.streamChat(request, new ChatListener() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(ProviderException e) {
                error.set(e);
            }
        });

        assertTrue(deltas.isEmpty());
        assertFalse(completed.get());
        assertEquals(expected, error.get());
        assertEquals(expected, provider.receivedError());
    }

    @Test
    void 请求构建校验model和消息必填() {
        assertThrows(IllegalStateException.class,
                () -> ChatRequest.builder().message(ChatMessage.of(ChatMessage.Role.USER, "hi")).build());
        assertThrows(IllegalStateException.class,
                () -> ChatRequest.builder().model("m").build());
    }

    @Test
    void 异常分类层次正确() {
        assertTrue(new AuthException("a") instanceof ProviderException);
        assertTrue(new RateLimitException("r") instanceof ProviderException);
        assertTrue(new ServerException("s") instanceof ProviderException);
        assertTrue(new NetworkException("n") instanceof ProviderException);
        assertTrue(new InvalidRequestException("i") instanceof ProviderException);
    }

    @Test
    void completeWithStopReasonCallsStringOverload() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.complete("end_turn"))));
        AtomicReference<String> received = new AtomicReference<>();
        provider.streamChat(request, new ChatListener() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onComplete(String stopReason) {
                received.set(stopReason);
            }

            @Override
            public void onError(ProviderException e) {
            }
        });
        assertEquals("end_turn", received.get());
    }

    @Test
    void usageActionInvokesOnUsage() {
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.usage(new Usage(5, 1, 3, 0)))));
        AtomicReference<Usage> received = new AtomicReference<>();
        provider.streamChat(request, new ChatListener() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onUsage(Usage u) {
                received.set(u);
            }

            @Override
            public void onError(ProviderException e) {
            }
        });
        assertEquals(new Usage(5, 1, 3, 0), received.get());
    }

    @Test
    void plainCompleteCallsStringOverloadWithNull() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.complete())));
        AtomicReference<String> received = new AtomicReference<>();
        provider.streamChat(request, new ChatListener() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onComplete(String stopReason) {
                received.set(stopReason);
            }

            @Override
            public void onError(ProviderException e) {
            }
        });
        assertNull(received.get());
    }
}
