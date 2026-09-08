package com.acode.context;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T6：自动压缩接入 Agent 主循环（触发前置、守卫、请求形态、Notice 事件）。 */
class AutoCompactAgentTest {

    @TempDir
    Path tempDir;

    /** 预填逼近触发点的历史：超大旧内容 + 末尾最新未答复 user（protected） */
    private static Conversation nearTriggerConversation() {
        Conversation c = new Conversation("m", false, 4096, 80_000); // 触发点 47_000
        c.addMessage(ChatMessage.of(USER, "x".repeat(240_000)));     // 60000 token 旧内容
        c.addMessage(ChatMessage.of(USER, "最新问题"));               // 未答复 user
        return c;
    }

    @Test
    void autoCompactsBeforeBuildingRequest() throws Exception {
        Conversation conversation = nearTriggerConversation();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("<summary>自动压缩摘要</summary>"), FakeProvider.complete()),
                List.of(FakeProvider.delta("收到，继续"), FakeProvider.complete())));
        ContextManager cm = new ContextManager(tempDir, provider, conversation);
        Agent agent = new Agent(provider, conversation, new ToolRegistry(), new ToolContext(tempDir), 10, cm);
        List<AgentEvent> events = ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());

        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size(), "先摘要请求，后正常请求");
        // 请求 0 = 摘要请求：不带工具、thinking 关闭、独立摘要 max_tokens
        assertTrue(requests.get(0).tools().isEmpty(), "摘要请求不带工具");
        assertFalse(requests.get(0).thinking(), "摘要请求 thinking 关闭");
        assertEquals(ContextPolicy.SUMMARY_MAX_TOKENS, requests.get(0).maxTokens(),
                "摘要 max_tokens = 独立摘要常量");
        // 请求 1 = 正常请求：自摘要(user) 起 + 独立边界 assistant + 末尾未答复 user 原文
        List<ChatMessage> round = requests.get(1).messages();
        assertEquals(3, round.size(), "请求消息数 = 重建后消息数");
        assertEquals(USER, round.get(0).role());
        assertTrue(round.get(0).content().contains("自动压缩摘要"), "自摘要消息起");
        assertEquals(ASSISTANT, round.get(1).role());
        assertTrue(round.get(1).content().contains("重新读取"), "边界提醒为独立 assistant 消息");
        assertEquals(USER, round.get(2).role());
        assertEquals("最新问题", round.get(2).content(), "末尾仍含该未答复 user 原文");
        // Notice 提示含"压缩"
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Notice
                && ((AgentEvent.Notice) e).message().contains("压缩")),
                "应有自动压缩 Notice");
        // 旧超大内容不再以全文留在历史
        assertTrue(conversation.history().stream().noneMatch(m -> m.content().contains("x".repeat(1000))),
                "超大旧内容已被摘要替换");
    }

    @Test
    void guardSkipsWhenHistoryFitsRetentionBudget() throws Exception {
        Conversation conversation = new Conversation("m", false, 4096, 80_000);
        conversation.addMessage(ChatMessage.of(USER, "短对话内容"));
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("好的"), FakeProvider.complete())));
        ContextManager cm = new ContextManager(tempDir, provider, conversation);
        Agent agent = new Agent(provider, conversation, new ToolRegistry(), new ToolContext(tempDir), 10, cm);
        ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        // 无摘要请求：正常请求是唯一一次调用，且 max_tokens 为对话级
        assertEquals(1, provider.receivedRequests().size());
        assertEquals(4096, provider.receivedRequests().get(0).maxTokens());
        assertEquals(2, conversation.messageCount(), "短历史不被压缩（seed + 回答）");
    }
}
