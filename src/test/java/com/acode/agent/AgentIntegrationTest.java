package com.acode.agent;

import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.agent.AgentEvent.StreamText;
import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.agent.AgentEvent.ToolUseEvent;
import com.acode.agent.AgentEvent.TurnComplete;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.DefaultToolset;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
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

class AgentIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

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

    /** 3 轮工具链：ReadFile → Bash → 最终文本，断言事件相对顺序与历史累积 */
    @Test
    void threeRoundChainEmitsSequencedEventsAndAccumulatesHistory() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "你好世界");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("开始处理"),
                        FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("继续"),
                        FakeProvider.toolUse("id-2", "Bash",
                                JSON.createObjectNode().put("command", "echo integration-ok")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("完成"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> list = untilLoop(events, 15000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        assertEquals(3, provider.receivedRequests().size());

        // 事件相对顺序：StreamText → ToolUseEvent → ToolResultEvent → TurnComplete × 2 → 最终文本 → LoopComplete
        assertEquals(10, list.size(), "事件总数：" + list);
        assertType(list, 0, StreamText.class);
        assertEquals("开始处理", ((StreamText) list.get(0)).text());
        assertType(list, 1, ToolUseEvent.class);
        assertEquals("id-1", ((ToolUseEvent) list.get(1)).toolId());
        assertEquals("ReadFile", ((ToolUseEvent) list.get(1)).toolName());
        assertType(list, 2, ToolResultEvent.class);
        assertEquals("id-1", ((ToolResultEvent) list.get(2)).toolId());
        assertType(list, 3, TurnComplete.class);
        assertEquals(1, ((TurnComplete) list.get(3)).turn());
        assertType(list, 4, StreamText.class);
        assertEquals("继续", ((StreamText) list.get(4)).text());
        assertType(list, 5, ToolUseEvent.class);
        assertEquals("id-2", ((ToolUseEvent) list.get(5)).toolId());
        assertEquals("Bash", ((ToolUseEvent) list.get(5)).toolName());
        assertType(list, 6, ToolResultEvent.class);
        assertEquals("id-2", ((ToolResultEvent) list.get(6)).toolId());
        assertTrue(((ToolResultEvent) list.get(6)).output().contains("integration-ok"),
                "Bash 工具结果应包含输出");
        assertType(list, 7, TurnComplete.class);
        assertEquals(2, ((TurnComplete) list.get(7)).turn());
        assertType(list, 8, StreamText.class);
        assertEquals("完成", ((StreamText) list.get(8)).text());
        assertType(list, 9, LoopComplete.class);
        assertEquals(3, ((LoopComplete) list.get(9)).totalTurns());

        // 逐轮请求：每轮历史含前轮 tool_result
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals("任务", requests.get(0).messages().get(0).content());
        assertLastBlockIsToolResult(requests.get(1), "id-1");
        assertLastBlockIsToolResult(requests.get(2), "id-2");
        assertTrue(requests.get(2).messages().stream()
                        .anyMatch(m -> m.content().contains("开始处理")),
                "历史应累积第 1 轮文本");
        assertTrue(requests.get(2).messages().stream()
                        .anyMatch(m -> m.content().contains("继续")),
                "历史应累积第 2 轮文本");
    }

    /** 超长工具结果入历史前截断（2000 字符） */
    @Test
    void oversizedToolResultTruncatedInRequestHistory() throws Exception {
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
        assertTrue(block.content().contains("已截断"), "超长结果应带截断提示");
        assertTrue(block.content().length() < 5000, "结果应被截断");
        assertEquals("id-1", block.toolUseId(), "tool_result 应对齐 tool_use id");
    }

    /** 取消发生在流式收集到 tool_use 之后：已收集调用补「已取消」，无悬空 */
    @Test
    void cancelDuringStreamPairsCollectedToolUseWithCancelledResult() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "数据");
        CountDownLatch streamStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.delta("已读到"),
                FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", file.toString())),
                listener -> {
                    streamStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        // 取消中断 await，随后 onComplete 被 collector 忽略
                    }
                    listener.onComplete();
                })));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        assertTrue(streamStarted.await(2, TimeUnit.SECONDS), "流式应已开始");
        agent.cancel();
        awaitTermination(agent, 3000);
        release.countDown();

        assertEquals(Agent.Termination.CANCELED, agent.termination());
        List<ChatMessage> history = agent.conversation().history();
        assertNoDanglingToolUses(history);
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr
                                && tr.toolUseId().equals("id-1")
                                && tr.isError() && tr.content().contains("已取消"))),
                "已收集的 tool_use 应补「已取消」结果");
    }

    /** plan 全流程：plan 模式交付落盘 → setPlanMode(false) 后工具列表恢复全量 */
    @Test
    void planFlowDeliversThenRestoresFullToolsAfterExit() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("计划正文"),
                        FakeProvider.toolUse("id-1", "ExitPlanMode", JSON.createObjectNode()),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("执行完成"), FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        agent.setPlanMode(true);
        BlockingQueue<AgentEvent> events = agent.run();
        untilLoop(events, 3000);

        assertEquals(Agent.Termination.PLAN_DELIVERED, agent.termination());
        Path plan = agent.planPath();
        assertTrue(plan != null && Files.exists(plan), "计划文件应落盘：" + plan);
        assertTrue(Files.readString(plan).contains("计划正文"));
        assertTrue(provider.receivedRequests().get(0).tools().stream()
                        .anyMatch(t -> "ExitPlanMode".equals(t.name())),
                "plan 请求应含交付工具");

        agent.setPlanMode(false);
        agent.run();
        untilLoop(events, 3000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());
        List<Tool> tools2 = provider.receivedRequests().get(1).tools();
        assertTrue(tools2.stream().noneMatch(t -> "ExitPlanMode".equals(t.name())),
                "退出 plan 后不应再发 ExitPlanMode");
        assertTrue(tools2.stream().anyMatch(t -> "WriteFile".equals(t.name())),
                "写工具应恢复下发");
    }

    private static void assertType(List<AgentEvent> list, int index, Class<?> type) {
        assertTrue(index < list.size(), "事件列表长度不足 " + (index + 1) + "：" + list);
        assertTrue(type.isInstance(list.get(index)),
                "第 " + (index + 1) + " 个事件应为 " + type.getSimpleName() + "，实际：" + list.get(index));
    }

    private static void assertLastBlockIsToolResult(ChatRequest request, String toolUseId) {
        ChatMessage last = request.messages().get(request.messages().size() - 1);
        assertEquals(ChatMessage.Role.USER, last.role());
        assertTrue(last.blocks().get(0) instanceof ToolResultBlock);
        assertEquals(toolUseId, ((ToolResultBlock) last.blocks().get(0)).toolUseId());
    }
}
