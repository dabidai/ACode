package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.ui.OutputPane;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(8000);
        return config;
    }

    @Test
    void singleStepToolLoopExecutesToolAndReturnsFinalText() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "你好世界");

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("文件内容是：你好世界"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读一下 a.txt", () -> false, () -> { });

        // 两轮请求：第一轮带工具，第二轮历史含 tool_result 回传
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size(), "应为两轮请求");
        assertFalse(requests.get(0).tools().isEmpty(), "第一轮请求应携带工具列表");
        List<ChatMessage> round2 = requests.get(1).messages();
        ChatMessage last = round2.get(round2.size() - 1);
        assertEquals(ChatMessage.Role.USER, last.role());
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertEquals("id-1", block.toolUseId(), "回传应关联原 tool_use id");
        assertFalse(block.isError(), "成功结果不应带错误标记");
        assertTrue(block.content().contains("你好世界"), "回传内容应为工具输出");

        // 界面：工具卡片成功 + 最终文本
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("ReadFile"), "应渲染工具卡片");
        assertTrue(joined.contains("成功"), "卡片应为成功状态");
        assertTrue(joined.contains("文件内容是：你好世界"), "应显示最终文本");

        // 对话历史：user → assistant(工具调用) → user(tool_result) → assistant(最终文本)
        assertEquals(4, controller.conversation().messageCount());
    }

    @Test
    void failedToolResultPassedBackWithErrorFlag() throws Exception {
        Path missing = tempDir.resolve("nope.txt"); // 不存在的文件

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", missing.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("文件不存在，请检查路径"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读 nope.txt", () -> false, () -> { });

        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size());
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.isError(), "失败工具结果应带错误标记");

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("失败"), "卡片应显示失败状态");
        assertTrue(joined.contains("文件不存在，请检查路径"));
    }

    @Test
    void secondRoundToolUseShowsTextAndHintOnly() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "你好世界");

        // 第一轮工具调用；第二轮又发起工具调用并带中间文本
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("已读取，继续处理"),
                        FakeProvider.toolUse("id-2", "Bash",
                                JSON.createObjectNode().put("command", "echo x")),
                        FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读文件并处理", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("连环工具调用暂不支持"), "第二轮仍 tool_use 应提示");

        // 对话历史仅追加第二轮文本，不追加第二轮 tool_use / tool_result
        List<ChatMessage> history = controller.conversation().history();
        ChatMessage last = history.get(history.size() - 1);
        assertEquals(ChatMessage.Role.ASSISTANT, last.role());
        assertEquals("已读取，继续处理", last.content());
    }

    @Test
    void hugeToolResultIsTruncatedBeforeEnteringHistory() throws Exception {
        Path file = tempDir.resolve("big.txt");
        Files.writeString(file, "x".repeat(5000));

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("读完了"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读大文件", () -> false, () -> { });

        List<ChatMessage> round2 = provider.receivedRequests().get(1).messages();
        ChatMessage last = round2.get(round2.size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.content().contains("已截断"), "超长结果入历史应带截断提示");
        assertTrue(block.content().length() < 5000, "结果应被截断");
    }

    @Test
    void plainQuestionUsesSingleRoundWithoutTools() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("普通回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("你好", () -> false, () -> { });

        assertEquals(1, provider.receivedRequests().size(), "无工具调用应只有一轮请求");
        assertTrue(String.join("\n", output.lines()).contains("普通回答"));
        assertEquals(2, controller.conversation().messageCount(), "user + assistant 两条");
    }

    @Test
    void renderHistoryMessageSummarizesToolBlocks() {
        ChatMessage assistant = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new TextBlock("先看文件"),
                new ToolUseBlock("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", "a.txt"))));
        String rendered = ConversationController.renderHistoryMessage(assistant);
        assertTrue(rendered.contains("先看文件"));
        assertTrue(rendered.contains("[工具调用 ReadFile(file_path=\"a.txt\")]"));

        ChatMessage toolResult = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ToolResultBlock("id-1", "文件内容", false)));
        String renderedResult = ConversationController.renderHistoryMessage(toolResult);
        assertTrue(renderedResult.contains("[工具结果 成功：文件内容]"));

        ChatMessage failure = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ToolResultBlock("id-2", "文件不存在", true)));
        assertTrue(ConversationController.renderHistoryMessage(failure).contains("[工具结果 失败"));
    }
}
