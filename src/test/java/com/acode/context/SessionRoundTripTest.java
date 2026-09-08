package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.session.Session;
import com.acode.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T9：压缩 → 存会话 → 读回 → 摘要可见、对话可继续、可再次压缩。 */
class SessionRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void saveRestoreRetainsSummaryAndCanRecompact() {
        // 1) 压缩
        Conversation original = new Conversation("m", false, 4096, 80_000);
        original.addMessage(ChatMessage.of(USER, "x".repeat(240_000)));
        original.addMessage(ChatMessage.of(USER, "原始问题"));
        FakeProvider provider = FakeProvider.streaming("<summary>存档摘要</summary>");
        ContextManager cm = new ContextManager(tempDir, provider, original);
        assertTrue(cm.executor().run(true).changed(), "首次压缩应成功");

        // 2) 存会话 → 读回
        SessionStore store = new SessionStore(tempDir.resolve("sessions"));
        store.save(new Session(null, System.currentTimeMillis(), original.history()));
        Session loaded = store.readLatest().orElseThrow();
        List<ChatMessage> restoredMessages = loaded.getMessages();
        assertTrue(restoredMessages.get(0).content().contains("存档摘要"), "读回历史含摘要消息");
        assertTrue(restoredMessages.stream().anyMatch(m -> m.role() == ASSISTANT
                && m.content().contains("重新读取")), "读回含边界提醒");

        // 3) 恢复进新会话后可继续、可再次压缩
        Conversation restored = new Conversation("m", false, 4096, 80_000);
        restoredMessages.forEach(restored::addMessage);
        restored.addMessage(ChatMessage.of(USER, "继续刚才的话题"));
        assertEquals("继续刚才的话题",
                restored.history().get(restored.history().size() - 1).content(),
                "追加后对话可继续");
        // 再加一批超大旧内容逼近触发点 → 再次自动可触发
        restored.addMessage(ChatMessage.of(USER, "y".repeat(240_000)));
        restored.addMessage(ChatMessage.of(USER, "新问题"));
        ContextManager cm2 = new ContextManager(tempDir,
                FakeProvider.streaming("<summary>二次压缩</summary>"), restored);
        assertTrue(cm2.executor().needsAutoCompact(), "恢复后的长会话仍可再次触发自动压缩");
        assertTrue(cm2.executor().run(true).changed(), "二次压缩正常触发");
    }
}
