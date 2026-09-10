package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.FakeProvider;
import com.acode.session.SessionEntry;
import com.acode.session.SessionRecorder;
import com.acode.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ch07 T9 → ch08 T5：压缩 → 会话文件整段重写 → 读回，摘要与边界提醒可见、对话可继续、可再次压缩。
 * 持久化模型已由"退出整存"换成"活跃追加 + 压缩后原子重写"，断言随之下移到行数口径。
 */
class SessionRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void rewriteAfterCompactionRestoresSummaryAndBoundaryHint() {
        Conversation original = new Conversation("m", false, 4096, 80_000);
        SessionRecorder recorder = new SessionRecorder(new SessionStore(tempDir));
        original.addAppendListener(recorder::append);
        original.addRebuildListener(recorder::rewrite);

        original.addMessage(ChatMessage.of(USER, "x".repeat(240_000)));
        original.addMessage(ChatMessage.of(USER, "原始问题"));
        assertEquals(2, SessionStore.readEntries(recorder.file()).size(),
                "压缩前：两条消息逐条追加成两行");

        ContextManager cm = new ContextManager(tempDir, FakeProvider.streaming("<summary>存档摘要</summary>"), original);
        assertTrue(cm.executor().run(true).changed(), "首次压缩应成功");

        // 压缩重建触发整段重写：文件内容 = 重建后的历史，无旧行残留
        List<SessionEntry> entries = SessionStore.readEntries(recorder.file());
        assertEquals(original.messageCount(), entries.size(), "重写后行数 = 重建后历史条数");
        assertTrue(entries.get(0).message().content().contains("存档摘要"), "读回历史含摘要消息");
        assertTrue(entries.stream().anyMatch(e -> e.message().role() == ASSISTANT
                && e.message().content().contains("重新读取")), "读回含边界提醒");
    }

    @Test
    void restoredHistoryCanContinueAndRecompact() {
        Conversation original = new Conversation("m", false, 4096, 80_000);
        SessionRecorder recorder = new SessionRecorder(new SessionStore(tempDir));
        original.addAppendListener(recorder::append);
        original.addRebuildListener(recorder::rewrite);
        original.addMessage(ChatMessage.of(USER, "x".repeat(240_000)));
        original.addMessage(ChatMessage.of(USER, "原始问题"));
        ContextManager cm = new ContextManager(tempDir, FakeProvider.streaming("<summary>摘要</summary>"), original);
        assertTrue(cm.executor().run(true).changed());

        // 读回进新会话后可继续
        Conversation restored = new Conversation("m", false, 4096, 80_000);
        SessionStore.readEntries(recorder.file()).forEach(e -> restored.addMessage(e.message()));
        restored.addMessage(ChatMessage.of(USER, "继续刚才的话题"));
        assertEquals("继续刚才的话题",
                restored.history().get(restored.history().size() - 1).content(), "追加后对话可继续");

        // 再加一批超大旧内容逼近触发点 → 再次自动可触发
        restored.addMessage(ChatMessage.of(USER, "y".repeat(240_000)));
        restored.addMessage(ChatMessage.of(USER, "新问题"));
        ContextManager cm2 = new ContextManager(tempDir,
                FakeProvider.streaming("<summary>二次压缩</summary>"), restored);
        assertTrue(cm2.executor().needsAutoCompact(), "恢复后的长会话仍可再次触发自动压缩");
        assertTrue(cm2.executor().run(true).changed(), "二次压缩正常触发");
    }
}
