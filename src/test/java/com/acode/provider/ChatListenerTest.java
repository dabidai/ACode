package com.acode.provider;

import org.junit.jupiter.api.Test;

/**
 * ChatListener default 方法行为：无参/带参 onComplete 互相委托的设计意图是
 * 「存量实现只覆写无参版仍能收到完成信号」。本测试验证零覆写的匿名实现调用
 * 任一 onComplete 不陷入无限递归。
 */
class ChatListenerTest {

    /** 零方法覆写的匿名实现（只实现两个抽象方法），触发双向 default 委托 */
    private static ChatListener zeroOverrideListener() {
        return new ChatListener() {
            @Override
            public void onDelta(String delta) {
            }

            @Override
            public void onError(ProviderException error) {
            }
        };
    }

    @Test
    void chatListenerDefaultMethodsDoNotRecurse() {
        ChatListener listener = zeroOverrideListener();
        // 期望：无参 → 带参 → 兜底结束，不爆栈
        listener.onComplete();
        listener.onComplete("max_tokens");
        listener.onToolUse(new ToolUseBlock("id-1", "ReadFile", null));
        listener.onUsage(new Usage(1, 0, 0, 0));
    }
}
