package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.ToolCallDisplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
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

        // 界面：● 运行行（带工具名）进回滚 + 成功输出块（绿色 ⎿）进模型 + 最终文本
        assertTrue(sw.toString().contains("ReadFile"), "运行行应带工具名进回滚");
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("  ⎿  "), "应渲染工具输出块");
        assertTrue(joined.contains(ToolCallDisplay.STYLE_OK), "卡片应为成功状态（绿色）");
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
        assertTrue(joined.contains(ToolCallDisplay.STYLE_ERR), "卡片应显示失败状态（红色）");
        assertTrue(joined.contains("文件不存在，请检查路径"));
    }

    @Test
    void multiRoundToolChainExecutesEveryRound() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "你好世界");

        // 三连工具链：ReadFile → Bash → 最终文本；第二轮 tool_use 应被真实执行而非提示放弃
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                        JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("已读取，继续处理"),
                        FakeProvider.toolUse("id-2", "Bash",
                                JSON.createObjectNode().put("command", "echo x")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("处理完成"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读文件并处理", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertFalse(joined.contains("连环工具调用暂不支持"), "多轮工具链不再提示放弃");

        // 三轮请求逐轮推进
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(3, requests.size(), "应为三轮请求");
        assertTrue(requests.get(1).messages().stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-1"))),
                "第二轮请求历史应含第一轮 tool_result");

        // 历史完整：第二轮 tool_use + tool_result 真实入史，最终文本收尾
        List<ChatMessage> history = controller.conversation().history();
        ChatMessage last = history.get(history.size() - 1);
        assertEquals(ChatMessage.Role.ASSISTANT, last.role());
        assertEquals("处理完成", last.content());
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolUseBlock tu && tu.id().equals("id-2"))),
                "历史应含第二轮 tool_use");
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-2"))),
                "历史应含第二轮 tool_result");
    }

    @Test
    void ctrlCDuringStreamInterruptsAgentAndKeepsHistoryConsistent() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "数据");
        CountDownLatch streamReachedBlock = new CountDownLatch(1);
        AtomicBoolean pressCtrlC = new AtomicBoolean(false);
        // 首轮流式：先发 tool_use，随后阻塞直到取消；取消中断 sleep 结束本流
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        listener -> {
                            streamReachedBlock.countDown();
                            try {
                                Thread.sleep(100_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            listener.onComplete();
                        }),
                List.of(FakeProvider.delta("第二次回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);

        Thread canceler = new Thread(() -> {
            try {
                streamReachedBlock.await(2, TimeUnit.SECONDS);
                pressCtrlC.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-canceler");
        canceler.start();
        controller.handleExchange("读文件", () -> pressCtrlC.get(), () -> { });
        canceler.join();

        // 首轮流式被中断：无第二轮请求，输出「已中断」
        assertEquals(1, provider.receivedRequests().size(), "取消应中断首轮，不再发起第二轮");
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("已中断"), "应输出「已中断」");
        // 已收集的 tool_use 补「已取消」结果，历史无悬空
        List<ChatMessage> history = controller.conversation().history();
        assertTrue(history.stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr
                                && tr.toolUseId().equals("id-1") && tr.isError()
                                && tr.content().contains("已取消"))),
                "已收集 tool_use 应补「已取消」结果");

        // 取消后可继续下一次 exchange
        controller.handleExchange("再次询问", () -> false, () -> { });
        assertEquals(2, provider.receivedRequests().size(), "取消后应可继续新对话");
        assertTrue(String.join("\n", output.lines()).contains("第二次回答"), "第二次 exchange 应正常生成");
    }

    @Test
    void maxIterationsCapsToolChainAtConfiguredLimit() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "数据");
        AppConfig config = config();
        config.setMaxIterations(2);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.toolUse("id-2", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config, false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("多步任务", () -> false, () -> { });

        assertEquals(2, provider.receivedRequests().size(), "maxIterations=2 应只发起两轮请求");
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("达到最大轮数"), "应输出触顶提示");
        // 已完成的第一轮工具结果保留在历史
        assertTrue(controller.conversation().history().stream().anyMatch(m -> m.blocks().stream()
                        .anyMatch(b -> b instanceof ToolResultBlock tr && tr.toolUseId().equals("id-1"))),
                "第一轮工具结果应保留在历史");
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
    void streamingDeltasTriggerAppendWriteOnFinishTurn() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("第一段"), FakeProvider.delta("第二段"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        CountingLive live = new CountingLive(80, 24);
        controller.setLive(live);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("你好", () -> false, () -> { });

        // 无换行的增量不逐段写屏，轮次收尾 finishTurn 一次性提交
        assertTrue(live.appends.get() >= 1, "流式收尾应触发追加写屏");
        assertTrue(sw.toString().contains("第一段第二段"), "最终文本应完整写屏：[" + sw + "]");
        assertTrue(String.join("\n", output.lines()).contains("第一段"), "内容模型仍应收到流式文本");
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

    @Test
    void deniedConfirmationPassesFailureBackToModel() throws Exception {
        Path target = tempDir.resolve("out.txt");
        AtomicInteger asks = new AtomicInteger();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "WriteFile",
                                JSON.createObjectNode().put("file_path", target.toString()).put("content", "hi")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("好的，我不覆盖文件"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setConfirmAnswerer(event -> {
            asks.incrementAndGet();
            assertEquals("WriteFile", event.toolName());
            return false;
        });
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("覆盖 out.txt", () -> false, () -> { });

        assertFalse(Files.exists(target), "拒绝后工具不应执行（文件不应创建）");
        assertEquals(1, asks.get(), "确认提示应恰好弹出一次");
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size(), "拒绝后模型应收到失败结果并再走一轮");
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.isError(), "拒绝结果应带错误标记");
        assertTrue(block.content().contains("拒绝"), "模型应收到「用户拒绝执行」原因");
    }

    @Test
    void approvedConfirmationExecutesTool() throws Exception {
        Path target = tempDir.resolve("out.txt");
        AtomicInteger asks = new AtomicInteger();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "WriteFile",
                                JSON.createObjectNode().put("file_path", target.toString()).put("content", "hi")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("已写入"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setConfirmAnswerer(event -> {
            asks.incrementAndGet();
            return true;
        });
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("写 out.txt", () -> false, () -> { });

        assertEquals(1, asks.get(), "确认提示应恰好弹出一次");
        assertEquals("hi", Files.readString(target), "批准后工具应执行并写入文件");
        List<ChatRequest> requests = provider.receivedRequests();
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertFalse(block.isError(), "批准后的工具结果不应带错误标记");
    }

    @Test
    void askUserSelectionPassedBackAsToolResult() throws Exception {
        ObjectNode args = JSON.createObjectNode().put("question", "先做哪个？");
        args.putArray("options").add("A").add("B");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "AskUser", args), FakeProvider.complete()),
                List.of(FakeProvider.delta("好的，先做 B"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setChoiceAnswerer(event -> {
            assertEquals(List.of("A", "B"), event.options());
            assertEquals("先做哪个？", event.question());
            return "B";
        });
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("帮我做个选择", () -> false, () -> { });

        // 首轮请求工具表含 AskUser；第二轮 tool_result 回传选中项 B
        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).tools().stream().anyMatch(t -> t.name().equals("AskUser")),
                "首轮请求应携带 AskUser 工具");
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertEquals("id-1", block.toolUseId(), "回传应关联原 tool_use id");
        assertFalse(block.isError(), "选中项应作为成功结果回传");
        assertTrue(block.content().contains("B"), "回传内容应为选中项文本");

        // 界面：AskUser 工具卡片进回滚；状态行「（已选择「B」）」由真实 answerChoicePrompt
        // 在 T9 真终端手测覆盖（setChoiceAnswerer 桩跳过 UI 写入）
        assertTrue(sw.toString().contains("AskUser"), "AskUser 工具卡片应进回滚：" + sw);
    }

    @Test
    void askUserCancelPassesFailureBackToModel() throws Exception {
        ObjectNode args = JSON.createObjectNode().put("question", "先做哪个？");
        args.putArray("options").add("A").add("B");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "AskUser", args), FakeProvider.complete()),
                List.of(FakeProvider.delta("那我自己决定"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setChoiceAnswerer(event -> null); // 用户取消
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("帮我做个选择", () -> false, () -> { });

        List<ChatRequest> requests = provider.receivedRequests();
        assertEquals(2, requests.size(), "取消后模型应收到失败结果并再走一轮");
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.isError(), "取消结果应带错误标记");
        assertTrue(block.content().contains("取消"), "模型应收到「用户取消选择」：" + block.content());
        assertTrue(sw.toString().contains("AskUser"), "AskUser 工具卡片应进回滚：" + sw);
    }

    /** 计数追加写屏次数的假渲染器（追加式路径）。 */
    static class CountingLive extends LiveRegionRenderer {
        final AtomicInteger appends = new AtomicInteger();

        CountingLive(int width, int height) {
            super(width, height);
        }

        @Override
        public void appendCommitted(Writer out, String text) {
            appends.incrementAndGet();
            super.appendCommitted(out, text);
        }
    }

    @Test
    void readFileShowsSummaryInsteadOfFileBodyInRollback() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "第一行\n第二行\n第三行");

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("文件读好了"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("读 a.txt", () -> false, () -> { });

        // 模型回传内容不变：第二轮 tool_result 仍含完整文件正文
        List<ChatRequest> requests = provider.receivedRequests();
        ChatMessage last = requests.get(1).messages().get(requests.get(1).messages().size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertTrue(block.content().contains("第一行"), "模型回传 tool_result 仍是完整文件内容");

        // 界面回滚：只出一行摘要 + 耗时，不含文件正文
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("返回 3 行（L1-3）"), "应渲染一行摘要：" + joined);
        assertTrue(joined.contains("  ⎿  "), "摘要行仍带 ⎿ 前缀");
        assertFalse(joined.contains("第二行"), "文件正文不应进回滚");
    }

    @Test
    void writeFileShowsGreenPlusLinesInRollback() throws Exception {
        Path target = tempDir.resolve("out.txt");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "WriteFile",
                                JSON.createObjectNode().put("file_path", target.toString()).put("content", "你好\n世界")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("写入完成"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setConfirmAnswerer(event -> true);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("写 out.txt", () -> false, () -> { });

        assertEquals("你好\n世界", Files.readString(target), "WriteFile 应真实执行");
        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("+ 你好"), "应渲染绿色 + 行：" + joined);
        assertTrue(joined.contains("+ 世界"));
        assertTrue(joined.contains(ToolCallDisplay.STYLE_OK), "+ 行应为绿色");
    }

    @Test
    void readFileFailureStillShowsRedError() throws Exception {
        Path missing = tempDir.resolve("nope.txt");
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", missing.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("文件不存在，请检查"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("读 nope", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains(ToolCallDisplay.STYLE_ERR), "失败仍显示红色：" + joined);
        assertTrue(joined.contains("文件不存在"), "失败正文应显示");
    }
}
