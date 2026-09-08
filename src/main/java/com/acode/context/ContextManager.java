package com.acode.context;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatProvider;

import java.nio.file.Path;

/**
 * 上下文管理装配层门面（每会话一次）：
 * 组合预算策略 + 落盘存储 + 冻结记账 + 大结果预算闸门 + 摘要执行器，作为注入 Agent / 手动命令的单入口。
 * {@link #reset()} 清空运行期状态（冻结记账、摘要熔断；落盘文件不删），由 conversation 的 clear 钩子联动。
 */
public class ContextManager {

    private final ContextPolicy policy;
    private final ToolResultBudget budget;
    private final CompactExecutor executor;

    public ContextManager(Path workingDirectory, ChatProvider provider, Conversation conversation) {
        this(workingDirectory, provider, conversation, new ContextPolicy());
    }

    public ContextManager(Path workingDirectory, ChatProvider provider, Conversation conversation, ContextPolicy policy) {
        this.policy = policy;
        SpillStore spillStore = new SpillStore(workingDirectory);
        ContentReplacementState state = new ContentReplacementState();
        this.budget = new ToolResultBudget(policy, spillStore, state);
        this.executor = new CompactExecutor(provider, conversation, policy);
    }

    public ContextPolicy policy() {
        return policy;
    }

    public ToolResultBudget budget() {
        return budget;
    }

    public CompactExecutor executor() {
        return executor;
    }

    /** 重置运行期状态：大结果落盘冻结记账 + 摘要连续失败熔断（落盘文件不删，见 spec Out of Scope） */
    public void reset() {
        budget.resetState();
        executor.resetBreaker();
    }
}
