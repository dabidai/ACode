package com.acode.conversation;

import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T1：整体估算（系统+环境+历史）、replaceAll 原子重建、clear 钩子。 */
class ConversationContextTest {

    @Test
    void overallEstimateSumsSystemEnvironmentAndHistory() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.setSystemPrompt("s".repeat(400));     // 100 token
        c.setEnvironment(ChatMessage.of(USER, "e".repeat(400))); // 100 token
        c.addMessage(ChatMessage.of(USER, "h".repeat(400)));     // 100 token
        assertEquals(300, c.estimateContextTokens(),
                "整体估算 = 系统提示 + 环境快照 + 历史三者之和");
    }

    @Test
    void overallEstimateEmptyHistoryStillCountsSystemAndEnvironment() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        assertEquals(0, c.estimateContextTokens());
        c.setSystemPrompt("s".repeat(800));      // 200 token
        c.setEnvironment(ChatMessage.of(USER, "e".repeat(800))); // 200 token
        assertEquals(400, c.estimateContextTokens(), "无消息时不误判为 0，仍含系统与环境");
    }

    @Test
    void replaceAllReplacesHistoryAndIgnoresStaleEpochWrites() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "旧"));
        long agentEpoch = c.nextEpoch(); // 旧 agent 线程捕获的代次
        c.nextEpoch();                    // 新 exchange 开启 → 旧代次失效
        c.replaceAll(List.of(
                ChatMessage.of(USER, "摘要"),
                ChatMessage.of(com.acode.provider.ChatMessage.Role.ASSISTANT, "边界提醒")));
        assertEquals(2, c.messageCount());
        assertEquals("摘要", c.history().get(0).content());
        c.addMessage(agentEpoch, ChatMessage.of(USER, "迟到"));
        assertEquals(2, c.messageCount(), "旧代次迟到写入被忽略");
    }

    @Test
    void replaceAllReplacesAtomicallyForRepeatedCalls() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "a"));
        c.replaceAll(List.of(ChatMessage.of(USER, "b")));
        c.replaceAll(List.of(ChatMessage.of(USER, "c"), ChatMessage.of(USER, "d")));
        assertEquals(List.of("c", "d"), c.history().stream().map(ChatMessage::content).toList());
    }

    @Test
    void clearInvokesRegisteredHookOnce() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        AtomicInteger calls = new AtomicInteger();
        c.addClearHook(calls::incrementAndGet);
        c.addMessage(ChatMessage.of(USER, "旧"));
        c.clear();
        assertEquals(1, calls.get(), "clear 应触发已注册钩子一次");
        assertEquals(0, c.messageCount());
    }

    @Test
    void clearHookSurvivesResetAndClearAgain() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        AtomicInteger calls = new AtomicInteger();
        c.addClearHook(calls::incrementAndGet);
        c.clear();
        c.clear();
        assertEquals(2, calls.get(), "钩子在后续 clear 仍被触发");
    }

    @Test
    void buildRequestDoesNotCropOverWindowHistory() {
        Conversation c = new Conversation("m", false, 4096, 5_000);
        c.setSystemPrompt("sys");
        for (int i = 0; i < 10; i++) {
            c.addMessage(ChatMessage.of(USER, "x".repeat(4_000))); // 每条 1000 token，远超窗口
        }
        ChatRequest request = c.buildRequest(List.of(), null);
        assertEquals(11, request.messages().size(),
                "预填超窗历史 → 请求消息数 = 1 系统 + 10 历史（无最旧裁剪静默删）");
        assertEquals(c.messageCount() + 1, request.messages().size());
    }

    @Test
    void estimateContextTokensAccountsToolResults() {
        Conversation c = new Conversation("m", false, 4096, 200_000);
        c.addMessage(ChatMessage.of(USER, "问题"));
        c.addToolResults(List.of(new ToolResultBlock("id-1", "内容".repeat(80), false)));
        assertTrue(c.estimateContextTokens() > 0, "tool_result 内容计入整体估算");
    }
}
