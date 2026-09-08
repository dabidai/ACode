package com.acode.context;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.Notice;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.provider.InvalidRequestException;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T7：紧急压缩（撞墙自救一次重试 / 持续超长只压一次 / 单条超窗走可见错误）。 */
class ForceCompactAgentTest {

    @TempDir
    Path tempDir;

    private static Conversation nearTriggerConversation() {
        // 可压缩（旧内容 > 保留预算 8000）但整体估算低于触发点，避免先触发自动压缩抢走脚本
        Conversation c = new Conversation("m", false, 4096, 200_000); // 触发点 167_000
        c.addMessage(ChatMessage.of(USER, "x".repeat(40_000)));       // 10000 token 旧内容
        c.addMessage(ChatMessage.of(USER, "本轮问题"));
        return c;
    }

    @Test
    void overlongThenForceCompactsOnceAndRetriesSuccessfully() throws Exception {
        Conversation conversation = nearTriggerConversation();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long"))),
                List.of(FakeProvider.delta("<summary>强制压缩摘要</summary>"), FakeProvider.complete()),
                List.of(FakeProvider.delta("重试成功"), FakeProvider.complete())));
        ContextManager cm = new ContextManager(tempDir, provider, conversation);
        Agent agent = new Agent(provider, conversation, new ToolRegistry(), new ToolContext(tempDir), 10, cm);
        List<AgentEvent> events = ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(3, requests.size(), "原请求 + 摘要 + 重试原请求各一次");
        assertEquals(ContextPolicy.SUMMARY_MAX_TOKENS, requests.get(1).maxTokens(), "第二次调用为摘要请求");
        ChatMessage retryLast = requests.get(2).messages().get(requests.get(2).messages().size() - 1);
        assertEquals(USER, retryLast.role());
        assertEquals("本轮问题", retryLast.content(), "重试请求仍含本轮用户输入原文");
        assertTrue(events.stream().anyMatch(e -> e instanceof Notice
                && ((Notice) e).message().contains("压缩")), "应有紧急压缩 Notice");
    }

    @Test
    void persistentOverlongCompactsOnceThenErrors() throws Exception {
        Conversation conversation = nearTriggerConversation();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long"))),
                List.of(FakeProvider.delta("<summary>已压缩</summary>"), FakeProvider.complete()),
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long")))));
        ContextManager cm = new ContextManager(tempDir, provider, conversation);
        Agent agent = new Agent(provider, conversation, new ToolRegistry(), new ToolContext(tempDir), 10, cm);
        List<AgentEvent> events = ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        assertEquals(Agent.Termination.ERROR, agent.termination(), "重试仍超长 → 正常错误终止");
        assertEquals(3, provider.receivedRequests().size(), "恰好压缩一次后重试一次");
        assertTrue(events.stream().anyMatch(e -> e instanceof ErrorEvent), "应有 ErrorEvent");
        assertTrue(conversation.history().get(0).content().contains("已压缩"),
                "历史已被压缩为摘要（只压一次后保留）");
    }

    @Test
    void singleHugeMessageResidualShowsVisibleErrorWithoutHistoryChange() throws Exception {
        // 单条 user 长文自身超窗：紧急压缩无法压到触发点以下 → 可见错误、历史不变
        Conversation conversation = new Conversation("m", false, 4096, 200_000);
        conversation.addMessage(ChatMessage.of(USER, "q".repeat(1_200_000))); // 30 万 token，单条即超窗
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("prompt is too long")))));
        ContextManager cm = new ContextManager(tempDir, provider, conversation);
        Agent agent = new Agent(provider, conversation, new ToolRegistry(), new ToolContext(tempDir), 10, cm);
        List<AgentEvent> events = ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        assertEquals(Agent.Termination.ERROR, agent.termination());
        assertEquals(1, conversation.messageCount(), "单条超窗压缩仍无法解决 → 历史不变");
        assertTrue(events.stream().anyMatch(e -> e instanceof ErrorEvent), "可见错误");
    }
}
