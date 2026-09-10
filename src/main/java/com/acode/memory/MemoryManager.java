package com.acode.memory;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatProvider;

import java.util.List;

/**
 * 记忆装配门面：组合两级存储与提取调度，作为 ConversationController 与 Agent 的单入口。
 *
 * <p>索引文本在会话启动时构建一次（会话内不刷新，保 system 提示字节稳定与 prompt cache 命中）；
 * 异步提取写入的新记忆要到下个会话才出现在提示里。
 */
public class MemoryManager {

    private final MemoryStore store;
    private final boolean autoEnabled;
    private final MemoryExtractionScheduler scheduler;

    public MemoryManager(MemoryStore store, Conversation conversation, ChatProvider provider,
                         boolean autoEnabled) {
        this.store = store;
        this.autoEnabled = autoEnabled;
        this.scheduler = new MemoryExtractionScheduler(
                new MemoryExtractor(provider, store, conversation));
    }

    public MemoryStore store() {
        return store;
    }

    public boolean autoEnabled() {
        return autoEnabled;
    }

    public MemoryExtractionScheduler scheduler() {
        return scheduler;
    }

    /** 注入 system 提示的索引文本（会话启动构建一次） */
    public String indexText() {
        return store.injectionText();
    }

    /** 取出并清空累计告警（记忆根不可写、索引截断、悬空指针等），供 UI 输出一次 */
    public List<String> drainWarnings() {
        return store.drainWarnings();
    }

    /** 每轮 Agent 循环自然结束后调用：自动提取关闭时跳过 */
    public void onTurnComplete() {
        if (autoEnabled) {
            scheduler.triggerAsync();
        }
    }

    /** 手动提取一次（/memory run），同步返回结果；不受自动开关影响 */
    public MemoryExtractor.Outcome extractNow() {
        return scheduler.runNow();
    }

    /** 历史重置点联动：作废进行中的提取结果并清运行态 */
    public void reset() {
        scheduler.reset();
    }
}
