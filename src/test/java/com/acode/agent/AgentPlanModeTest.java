package com.acode.agent;

import com.acode.agent.AgentEvent.LoopComplete;
import com.acode.conversation.Conversation;
import com.acode.prompt.SystemReminder;
import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.DefaultToolset;
import com.acode.tool.Permission;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanModeTest {

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

    @Test
    void planModeFiltersToolsToReadPlusExitPlanMode() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        agent.setPlanMode(true);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 3000);
        List<Tool> tools = provider.receivedRequests().get(0).tools();
        Set<String> names = new HashSet<>();
        for (Tool tool : tools) {
            names.add(tool.name());
        }
        assertTrue(names.contains("ExitPlanMode"), "plan 模式应包含交付工具");
        assertTrue(names.contains("ReadFile"), "读类工具应保留");
        assertFalse(names.contains("WriteFile"), "写工具不应下发");
        assertFalse(names.contains("EditFile"), "写工具不应下发");
        assertFalse(names.contains("Bash"), "命令工具不应下发");
        for (Tool tool : tools) {
            if (!"ExitPlanMode".equals(tool.name())) {
                assertEquals(Permission.READ, tool.permission(), tool.name() + " 应为读类");
            }
        }
    }

    @Test
    void nonPlanModeExcludesExitPlanMode() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 3000);
        List<Tool> tools = provider.receivedRequests().get(0).tools();
        assertTrue(tools.stream().noneMatch(t -> "ExitPlanMode".equals(t.name())),
                "普通模式不应下发 ExitPlanMode");
        assertEquals(6, tools.size(), "六个内置工具全部下发");
    }

    @Test
    void exitPlanModeDeliversPlanAndWritesFile() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.delta("# 我的计划\n第一步先做 X"),
                FakeProvider.toolUse("id-1", "ExitPlanMode", JSON.createObjectNode()),
                FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        Agent agent = start(provider, 20, registry);
        agent.setPlanMode(true);
        BlockingQueue<AgentEvent> events = agent.run();

        List<AgentEvent> list = untilLoop(events, 3000);
        assertEquals(Agent.Termination.PLAN_DELIVERED, agent.termination());
        assertTrue(list.stream().anyMatch(e -> e instanceof LoopComplete));
        Path plan = agent.planPath();
        assertTrue(plan != null && Files.exists(plan), "计划文件应落盘：" + plan);
        assertTrue(Files.readString(plan).contains("# 我的计划"), "计划正文应写入文件");
        assertNoDanglingToolUses(agent.conversation().history());
    }

    @Test
    void firstRequestUsesFullReminderSecondUsesSparse() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "data");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("计划正文"),
                        FakeProvider.toolUse("id-2", "ExitPlanMode", JSON.createObjectNode()),
                        FakeProvider.complete())));
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);
        Agent agent = start(provider, 20, registry);
        agent.setPlanMode(true);
        BlockingQueue<AgentEvent> events = agent.run();

        untilLoop(events, 5000);
        assertEquals(Agent.Termination.PLAN_DELIVERED, agent.termination());
        ChatMessage first = lastMessage(provider, 0);
        assertEquals(ChatMessage.Role.USER, first.role(), "提醒应为 user 角色");
        assertTrue(first.content().startsWith("<system-reminder>"), "提醒应带 system-reminder 标签");
        assertEquals(SystemReminder.wrap(PlanModePrompt.buildReminder(1)).content(),
                first.content(), "首轮提醒应为完整版");
        assertEquals(SystemReminder.wrap(PlanModePrompt.buildReminder(2)).content(),
                lastMessage(provider, 1).content(), "后续轮提醒应为稀疏版");
    }

    @Test
    void reminderCadenceRepeatsFullEveryFifthRound() {
        String full = PlanModePrompt.buildReminder(1);
        String sparse = PlanModePrompt.buildReminder(2);
        assertEquals(sparse, PlanModePrompt.buildReminder(3));
        assertEquals(sparse, PlanModePrompt.buildReminder(4));
        assertEquals(sparse, PlanModePrompt.buildReminder(5));
        assertEquals(full, PlanModePrompt.buildReminder(6));
        assertEquals(sparse, PlanModePrompt.buildReminder(7));
        assertEquals(sparse, PlanModePrompt.buildReminder(10));
        assertEquals(full, PlanModePrompt.buildReminder(11));
        assertEquals(full, PlanModePrompt.buildReminder(16));
    }

    private static ChatMessage lastMessage(FakeProvider provider, int requestIndex) {
        List<ChatMessage> messages = provider.receivedRequests().get(requestIndex).messages();
        return messages.get(messages.size() - 1);
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
}
