package com.acode.context;

import com.acode.agent.Agent;
import com.acode.agent.AgentEvent;
import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.ToolResultBlock;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T3：Agent 工具结果走 ToolResultBudget（超长落盘 + 预览路径；冻结跨轮）。 */
class AgentToolResultBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HUGE = "B".repeat(120_000);

    @TempDir
    Path tempDir;

    /** 固定返回超大正文的桩工具 */
    private static final class BigOutTool implements Tool {
        @Override
        public String name() {
            return "BigOut";
        }

        @Override
        public String description() {
            return "输出超长内容";
        }

        @Override
        public Permission permission() {
            return Permission.READ;
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode input, ToolContext context) {
            return ToolResult.success(HUGE);
        }
    }

    @Test
    void overLimitResultSpillsToFileAndHistoryKeepsPreviewPath() throws Exception {
        Conversation conversation = new Conversation("m", false, 4096, 200_000);
        conversation.addMessage(ChatMessage.of(USER, "执行 BigOut"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BigOutTool());
        ContextManager cm = new ContextManager(tempDir, FakeProvider.streaming("占位"), conversation);

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("tool-1", "BigOut", JSON.createObjectNode()), FakeProvider.complete()),
                List.of(FakeProvider.delta("搞定"), FakeProvider.complete())));
        Agent agent = new Agent(provider, conversation, registry, new ToolContext(tempDir), 5, cm);
        List<AgentEvent> events = ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        assertEquals(Agent.Termination.NORMAL, agent.termination());

        // 全文落盘
        Path file = tempDir.resolve(".acode/tool-results/tool-1.txt");
        assertTrue(Files.exists(file), "超长结果应落盘");
        assertEquals(HUGE, Files.readString(file, StandardCharsets.UTF_8));

        // 历史（下一轮请求）里是预览+路径，不是全文、无旧截断后缀
        var requests = provider.receivedRequests();
        assertEquals(2, requests.size());
        ChatMessage toolRound = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) toolRound.blocks().get(0);
        assertTrue(block.content().contains("tool-1.txt"), "预览含可读路径");
        assertTrue(block.content().contains("…"), "预览含省略标记");
        assertFalse(block.content().equals(HUGE), "历史不含超长全文（只是前 2048 字符预览）");
        assertTrue(block.content().length() < HUGE.length(), "预览长度远小于全文");
        assertFalse(block.content().contains("结果过长，已截断"), "不再出现旧截断后缀");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResultEvent),
                "工具执行事件照常流出");
    }

    @Test
    void freezeStateSurvivesAcrossRoundsSameIdNotSpilledTwice() throws Exception {
        // 同批一次决策后，若同 id 再现（第二进同一批/后续批）则重放预览不重写
        ContextManager cm = new ContextManager(tempDir, FakeProvider.streaming("占位"), new Conversation("m", false, 4096, 200_000));
        List<ToolResultBlock> first = cm.budget().process(List.of(
                new ToolResultBudget.Item("tool-freeze", "F".repeat(60_000), false)));
        Path file = new SpillStore(tempDir).resolve("tool-freeze");
        String firstPreview = first.get(0).content();
        List<ToolResultBlock> second = cm.budget().process(List.of(
                new ToolResultBudget.Item("tool-freeze", "G".repeat(80_000), false)));
        assertEquals(firstPreview, second.get(0).content(), "同 id 再现 → 同一预览（冻结）");
        assertEquals("F".repeat(60_000), Files.readString(file), "不重写文件");
    }

    @Test
    void resultWithinLimitStaysFullInHistory() throws Exception {
        Conversation conversation = new Conversation("m", false, 4096, 200_000);
        conversation.addMessage(ChatMessage.of(USER, "执行 SmallOut"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "SmallOut";
            }

            @Override
            public String description() {
                return "短输出";
            }

            @Override
            public Permission permission() {
                return Permission.READ;
            }

            @Override
            public JsonNode inputSchema() {
                return JSON.createObjectNode();
            }

            @Override
            public ToolResult execute(JsonNode input, ToolContext context) {
                return ToolResult.success("短正文OK");
            }
        });
        ContextManager cm = new ContextManager(tempDir, FakeProvider.streaming("占位"), conversation);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("tool-2", "SmallOut", JSON.createObjectNode()), FakeProvider.complete()),
                List.of(FakeProvider.delta("完成"), FakeProvider.complete())));
        Agent agent = new Agent(provider, conversation, registry, new ToolContext(tempDir), 5, cm);
        ContextTestSupport.untilLoop(agent.run(), 5_000);
        ContextTestSupport.awaitNotRunning(agent, 5_000);
        var requests = provider.receivedRequests();
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ContentBlock block = last.blocks().get(last.blocks().size() - 1);
        if (block instanceof ToolResultBlock tr) {
            assertEquals("短正文OK", tr.content(), "≤ 上限 → 历史全文保留");
        }
        assertFalse(Files.exists(tempDir.resolve(".acode/tool-results/tool-2.txt")), "未超上限不落盘");
    }
}
