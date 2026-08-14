package com.acode.agent;

import com.acode.agent.AgentEvent.ErrorEvent;
import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.RetryEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.RateLimitException;
import com.acode.provider.ServerException;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.DefaultToolset;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 装配：注册全部内置工具 + 追加 user 消息。调用方随后 run() 启动循环并持有返回的事件队列 */
    private Agent start(FakeProvider provider, int maxIterations, ToolRegistry registry) {
        Conversation conversation = new Conversation("test", false, 4096, 8000);
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.USER, "任务"));
        return new Agent(provider, conversation, registry,
                new ToolContext(tempDir), maxIterations);
    }

    private static List<AgentEvent> untilLoop(BlockingQueue<AgentEvent> queue, long timeoutMs) throws Exception {
        List<AgentEvent> list = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            list.add(event);
            if (event instanceof LoopComplete) {
                return list;
            }
        }
        throw new AssertionError("未收到 LoopComplete，已收集：" + list);
    }

    private static void awaitTermination(Agent agent, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && agent.termination() == Agent.Termination.NORMAL) {
            Thread.sleep(20);
        }
        assertTrue(agent.termination() != Agent.Termination.NORMAL, "循环应已终止");
    }

    @Test
    void singleTurnWithoutToolsCompletesNormally() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("你好"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> eventsList = untilLoop(events, 3000);
        assertEquals(1, provider.receivedRequests().size());
        assertTrue(eventsList.stream().anyMatch(e -> e instanceof LoopComplete));
        LoopComplete done = (LoopComplete) eventsList.stream()
                .filter(e -> e instanceof LoopComplete).findFirst().orElseThrow();
        assertEquals(1, done.totalTurns());
        assertEquals(Agent.Termination.NORMAL, agent.termination());
    }

    @Test
    void twoTurnToolLoopFeedsResultBack() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "你好世界");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("文件内容是：你好世界"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 5000);
        assertEquals(2, provider.receivedRequests().size());
        assertEquals(Agent.Termination.NORMAL, agent.termination());

        // 第 2 轮请求历史含第 1 轮 tool_result
        List<ChatMessage> round2 = provider.receivedRequests().get(1).messages();
        ChatMessage last = round2.get(round2.size() - 1);
        assertEquals(ChatMessage.Role.USER, last.role());
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertEquals("id-1", block.toolUseId());
        assertFalse(block.isError());
        assertTrue(block.content().contains("你好世界"));
    }

    @Test
    void threeTurnChainAccumulatesHistory() throws Exception {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", a.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.toolUse("id-2", "ReadFile",
                                JSON.createObjectNode().put("file_path", b.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("搞定"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> eventsList = untilLoop(events, 8000);
        assertEquals(3, provider.receivedRequests().size());
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        long turnCompletes = eventsList.stream().filter(e -> e instanceof TurnComplete).count();
        assertEquals(2, turnCompletes, "两个工具轮各发一次 TurnComplete");
    }

    @Test
    void maxIterationsTouchDownStopsWithoutExecutingFinalTools() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "数据");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.toolUse("id-2", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 2, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 5000);
        assertEquals(Agent.Termination.MAX_ITERATIONS, agent.termination());
        assertEquals(2, provider.receivedRequests().size(), "第 2 轮触顶仍发请求但不再执行");
        // 触顶轮工具不执行：历史只含第 1 轮结果
        List<ChatMessage> history = agent.conversation().history();
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock)),
                "已完成（第 1 轮）工具结果应保留在历史");
        assertNoDanglingToolUses(history);
    }

    @Test
    void cancelDuringStreamEndsLoopWithoutLoopComplete() throws Exception {
        CountDownLatch streamStarted = new CountDownLatch(1);
        CountDownLatch releaseStream = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                listener -> {
                    streamStarted.countDown();
                    try {
                        releaseStream.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    listener.onDelta("延迟文本");
                    listener.onComplete();
                })));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        assertTrue(streamStarted.await(2, TimeUnit.SECONDS), "流式应已开始");
        agent.cancel();
        awaitTermination(agent, 3000);
        releaseStream.countDown();

        assertEquals(Agent.Termination.CANCELED, agent.termination());
        List<AgentEvent> drained = new ArrayList<>();
        events.drainTo(drained);
        assertFalse(drained.stream().anyMatch(e -> e instanceof LoopComplete),
                "取消后不发 LoopComplete");
    }

    @Test
    void cancelDuringToolExecutionPairsCancelledPlaceholder() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        Tool blocking = new BlockingTool("Blocking", toolStarted, releaseTool);
        ToolRegistry registry = new ToolRegistry();
        registry.register(blocking);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "Blocking", JSON.createObjectNode()),
                        FakeProvider.complete())));

        Agent agent = start(provider, 20, registry);
        agent.run();
        assertTrue(toolStarted.await(2, TimeUnit.SECONDS), "工具应已开始执行");
        agent.cancel();
        releaseTool.countDown();
        awaitTermination(agent, 3000);

        assertEquals(Agent.Termination.CANCELED, agent.termination());
        // 历史一致性：id-1 有对应「已取消」结果
        List<ChatMessage> history = agent.conversation().history();
        assertNoDanglingToolUses(history);
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr
                                && tr.isError() && tr.content().contains("已取消"))),
                "未完成工具应补「已取消」结果入历史");
    }

    @Test
    void maxTokensTruncationInjectsContinuationAndContinues() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("部分文本"), FakeProvider.complete("max_tokens")),
                List.of(FakeProvider.delta("继续的文本"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 3000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        assertEquals(2, provider.receivedRequests().size(), "截断恢复消耗下一轮请求");
        List<ChatMessage> history = agent.conversation().history();
        assertTrue(history.stream().anyMatch(m -> m.content().contains("输出被截断，请从断点继续")),
                "应注入继续提示");
        assertTrue(history.stream().anyMatch(m -> m.content().contains("继续的文本")));
    }

    @Test
    void fourConsecutiveTruncationsTerminateNormally() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("t1"), FakeProvider.complete("max_tokens")),
                List.of(FakeProvider.delta("t2"), FakeProvider.complete("max_tokens")),
                List.of(FakeProvider.delta("t3"), FakeProvider.complete("max_tokens")),
                List.of(FakeProvider.delta("t4"), FakeProvider.complete("max_tokens"))));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 3000);
        assertEquals(Agent.Termination.NORMAL, agent.termination(), "超 3 次截断按正常终止");
        assertEquals(4, provider.receivedRequests().size());
        // 前 3 次注入继续提示，第 4 次不注入
        long hints = agent.conversation().history().stream()
                .filter(m -> m.content().contains("输出被截断，请从断点继续")).count();
        assertEquals(3, hints);
    }

    @Test
    void streamErrorEmitsErrorEventAndErrorTermination() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new InvalidRequestException("参数错误")))));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> eventsList = untilLoop(events, 3000);
        assertEquals(Agent.Termination.ERROR, agent.termination());
        assertTrue(eventsList.stream().anyMatch(e -> e instanceof ErrorEvent));
    }

    @Test
    void retryableErrorRetriesThenSucceeds() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new RateLimitException("限流了"))),
                List.of(FakeProvider.delta("重试成功"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> eventsList = untilLoop(events, 8000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        assertTrue(eventsList.stream().anyMatch(e -> e instanceof RetryEvent));
        RetryEvent retry = (RetryEvent) eventsList.stream()
                .filter(e -> e instanceof RetryEvent).findFirst().orElseThrow();
        assertTrue(retry.waitMs() > 0, "重试退避应为正数");
        assertEquals(2, provider.receivedRequests().size(), "重试消耗下一份脚本");
    }

    @Test
    void retriesExhaustedAfterTwoRetries() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new ServerException("服务端错误"))),
                List.of(FakeProvider.error(new ServerException("服务端错误"))),
                List.of(FakeProvider.error(new ServerException("服务端错误")))));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> eventsList = untilLoop(events, 15000);
        assertEquals(Agent.Termination.ERROR, agent.termination());
        assertEquals(3, provider.receivedRequests().size(), "首次 + 2 次重试共 3 次请求");
        assertTrue(eventsList.stream().anyMatch(e -> e instanceof ErrorEvent));
    }

    @Test
    void hugeToolResultTruncatedBeforeEnteringHistory() throws Exception {
        Path file = tempDir.resolve("big.txt");
        Files.writeString(file, "x".repeat(5000));
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("读完了"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 5000);
        List<ChatMessage> round2 = provider.receivedRequests().get(1).messages();
        ChatMessage last = round2.get(round2.size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.content().contains("已截断"), "超长结果入历史应带截断提示");
        assertTrue(block.content().length() < 5000, "结果应被截断");
    }

    private static void assertNoDanglingToolUses(List<ChatMessage> history) {
        Set<String> issued = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        for (ChatMessage message : history) {
            for (ContentBlock block : message.blocks()) {
                if (block instanceof ToolUseBlock tu) {
                    issued.add(tu.id());
                } else if (block instanceof ToolResultBlock tr) {
                    resolved.add(tr.toolUseId());
                }
            }
        }
        assertEquals(issued, resolved, "每个 tool_use 都应有对应 tool_result");
    }

    /** 记录开始 + 可阻塞释放的桩工具 */
    private static class BlockingTool implements Tool {
        final String name;
        final CountDownLatch started;
        final CountDownLatch release;

        BlockingTool(String name, CountDownLatch started, CountDownLatch release) {
            this.name = name;
            this.started = started;
            this.release = release;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test blocking tool";
        }

        @Override
        public Permission permission() {
            return Permission.WRITE;
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode input, ToolContext context) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("blocked-done");
        }
    }
}
