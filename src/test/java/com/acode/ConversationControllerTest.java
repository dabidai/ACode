package com.acode;

import com.acode.config.AppConfig;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionResponse;
import com.acode.permission.RuleEngine;
import com.acode.prompt.PromptBuilder;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.provider.InvalidRequestException;
import com.acode.provider.RateLimitException;
import com.acode.provider.TextBlock;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
import com.acode.provider.Usage;
import com.acode.ui.HistoryRenderer;
import com.acode.ui.LiveRegionRenderer;
import com.acode.ui.OutputPane;
import com.acode.ui.ToolCallDisplay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // 本组测试断言精确的请求次数：关掉每轮结束的异步记忆提取
        config.setMemoryAuto(false);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("读大文件", () -> false, () -> { });

        List<ChatMessage> round2 = provider.receivedRequests().get(1).messages();
        ChatMessage last = round2.get(round2.size() - 1);
        ToolResultBlock block = (ToolResultBlock) last.blocks().get(0);
        assertFalse(block.content().contains("已截断"), "ch07：移除旧的一刀切截断后缀");
        assertTrue(block.content().length() >= 4000, "结果不再被截断到 2000 上限，应保留全文");
    }

    @Test
    void plainQuestionUsesSingleRoundWithoutTools() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("普通回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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
        String rendered = HistoryRenderer.renderHistoryMessage(assistant);
        assertTrue(rendered.contains("先看文件"));
        assertTrue(rendered.contains("[工具调用 ReadFile(file_path=\"a.txt\")]"));

        ChatMessage toolResult = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ToolResultBlock("id-1", "文件内容", false)));
        String renderedResult = HistoryRenderer.renderHistoryMessage(toolResult);
        assertTrue(renderedResult.contains("[工具结果 成功：文件内容]"));

        ChatMessage failure = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ToolResultBlock("id-2", "文件不存在", true)));
        assertTrue(HistoryRenderer.renderHistoryMessage(failure).contains("[工具结果 失败"));
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
        controller.setProjectRoot(tempDir);
        controller.setConfirmAnswerer(event -> {
            asks.incrementAndGet();
            assertEquals("WriteFile", event.toolName());
            return PermissionResponse.DENY;
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
        controller.setProjectRoot(tempDir);
        controller.setConfirmAnswerer(event -> {
            asks.incrementAndGet();
            return PermissionResponse.ALLOW;
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
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

    @Test
    void requestStartsWithSystemPromptAndEnvironmentReminder() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("普通回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("你好", () -> false, () -> { });

        List<ChatMessage> messages = provider.receivedRequests().get(0).messages();
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).role(), "请求首位应为 SYSTEM");
        assertEquals(PromptBuilder.buildSystemPrompt(), messages.get(0).content(), "SYSTEM 应为七模块提示词");
        assertEquals(ChatMessage.Role.USER, messages.get(1).role(), "SYSTEM 后应为环境 system-reminder");
        assertTrue(messages.get(1).content().startsWith("<system-reminder>"), "环境消息应带 system-reminder 标签");
        assertTrue(messages.get(1).content().contains("# Environment"), "环境消息应含 # Environment 段落");
        assertTrue(controller.conversation().history().stream()
                        .noneMatch(m -> m.content().startsWith("<system-reminder>")),
                "环境消息不应进历史");
        assertEquals(2, controller.conversation().messageCount(), "历史仍只有 user + assistant");
    }

    @Test
    void clearKeepsEnvironmentInjectedInNextRequest() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("第一次"), FakeProvider.complete()),
                List.of(FakeProvider.delta("第二次"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("你好", () -> false, () -> { });
        controller.conversation().clear();
        controller.handleExchange("再问", () -> false, () -> { });

        List<ChatMessage> messages = provider.receivedRequests().get(1).messages();
        assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).role(), "CLEAR 后请求仍应以 SYSTEM 开头");
        assertTrue(messages.get(1).content().startsWith("<system-reminder>"), "CLEAR 后环境仍注入");
    }

    @Test
    void usageFootnotePrintedOnTurnComplete() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(List.of(
                FakeProvider.usage(new Usage(100, 20, 80, 5)),
                FakeProvider.delta("回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("你好", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("usage: in 100 · cache_read 80 · cache_write 5 · out 20"),
                "TurnComplete 应输出 usage 脚注行：" + joined);
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
        controller.setProjectRoot(tempDir);
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
        controller.setProjectRoot(tempDir);
        controller.setConfirmAnswerer(event -> PermissionResponse.ALLOW);
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
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("读 nope", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains(ToolCallDisplay.STYLE_ERR), "失败仍显示红色：" + joined);
        assertTrue(joined.contains("文件不存在"), "失败正文应显示");
    }

    // ---- T8 /permission-mode 运行时切档 ----

    private static PermissionChecker injectChecker(ConversationController controller, Path projectRoot) {
        RuleEngine rules = new RuleEngine(
                projectRoot.resolve("u.yaml"), projectRoot.resolve("p.yaml"), projectRoot.resolve("l.yaml"));
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, projectRoot, rules);
        controller.setPermissionChecker(checker);
        return checker;
    }

    @Test
    void permissionModeCommandSwitchesCheckerMode() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        PermissionChecker checker = injectChecker(controller, tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);

        controller.handlePermissionMode("acceptEdits", new LiveRegionRenderer(80, 24), sw);
        assertEquals(PermissionMode.ACCEPT_EDITS, checker.mode());
        assertTrue(sw.toString().contains("已切换到权限模式：acceptEdits"));
    }

    @Test
    void permissionModeTrailingWhitespaceIsTrimmed() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        PermissionChecker checker = injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        controller.handlePermissionMode("acceptEdits  ", new LiveRegionRenderer(80, 24), new StringWriter());
        assertEquals(PermissionMode.ACCEPT_EDITS, checker.mode());
    }

    @Test
    void permissionModeCaseSensitiveRejectsUppercase() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        PermissionChecker checker = injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        controller.handlePermissionMode("ACCEPT_EDITS", new LiveRegionRenderer(80, 24), new StringWriter());
        assertEquals(PermissionMode.DEFAULT, checker.mode(), "大小写敏感：非法值模式不变");
    }

    @Test
    void permissionModeExtraArgRejected() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        PermissionChecker checker = injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        controller.handlePermissionMode("acceptEdits extra", new LiveRegionRenderer(80, 24), new StringWriter());
        assertEquals(PermissionMode.DEFAULT, checker.mode(), "多余参数非法、模式不变");
    }

    @Test
    void permissionModeInvalidValueKeepsModeAndPrintsError() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        PermissionChecker checker = injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handlePermissionMode("yolo", new LiveRegionRenderer(80, 24), sw);
        assertEquals(PermissionMode.DEFAULT, checker.mode());
        assertTrue(sw.toString().contains("非法权限模式"));
    }

    @Test
    void permissionModeNoArgPrintsCurrentMode() {
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handlePermissionMode("", new LiveRegionRenderer(80, 24), sw);
        assertTrue(sw.toString().contains("当前权限模式：default"));
    }

    @Test
    void permissionModeSwitchDoesNotPersistToConfig() {
        AppConfig config = config();
        ConversationController controller = new ConversationController(FakeProvider.scripted(List.of()), config, false);
        injectChecker(controller, tempDir);
        controller.setOutput(new OutputPane());
        controller.handlePermissionMode("acceptEdits", new LiveRegionRenderer(80, 24), new StringWriter());
        assertNull(config.getPermissionMode(), "运行时切档不应写回 config");
    }

    // ---- P0-1 awaitLoopEnd 超时路径（控制器级） ----

    /**
     * 取舍说明：控制器级无法编排滞留 agent——ToolRegistry 为私有字段无注入点，注册不了
     * 吞中断的桩工具（AgentTest.UnstoppableTool 那样）；provider 阻塞也不滞留 agent 线程
     * （stream() 20ms 轮询取消即退）。「旧 agent 残留写入被 epoch 忽略」已由
     * AgentTest.staleAgentCannotWriteIntoNextAgentTurn 确定性覆盖。
     * 本测试验证可测部分：awaitLoopEnd 超时预算压到 0 时取消路径仍快速返回（UI 不挂死、
     * 不等 5 秒），且随后新 exchange 立即可用。
     */
    @Test
    void staleAgentResultIgnoredAfterAwaitLoopEndTimeout() throws Exception {
        long saved = ExchangeRunner.awaitLoopEndTimeoutMillis;
        try {
            ExchangeRunner.awaitLoopEndTimeoutMillis = 0;
            CountDownLatch streamStarted = new CountDownLatch(1);
            AtomicBoolean pressCtrlC = new AtomicBoolean(false);
            FakeProvider provider = FakeProvider.scripted(List.of(
                    List.of(listener -> {
                        streamStarted.countDown();
                        try {
                            Thread.sleep(100_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        listener.onComplete();
                    }),
                    List.of(FakeProvider.delta("新回合回答"), FakeProvider.complete())));
            ConversationController controller = new ConversationController(provider, config(), false);
            controller.setProjectRoot(tempDir);
            OutputPane output = new OutputPane();
            controller.setOutput(output);

            Thread canceler = new Thread(() -> {
                try {
                    streamStarted.await(2, TimeUnit.SECONDS);
                    pressCtrlC.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "test-canceler");
            canceler.start();
            long startMs = System.currentTimeMillis();
            controller.handleExchange("滞留任务", () -> pressCtrlC.get(), () -> { });
            long elapsed = System.currentTimeMillis() - startMs;
            canceler.join();

            assertTrue(elapsed < 3000,
                    "awaitLoopEnd 预算为 0 时取消路径应快速返回（UI 不挂死），实际 " + elapsed + " ms");
            assertTrue(String.join("\n", output.lines()).contains("已中断"), "应输出「已中断」");

            // 取消后新 exchange 立即可用：走下一 epoch，旧 agent 残留被忽略
            controller.handleExchange("再次询问", () -> false, () -> { });
            assertEquals(2, provider.receivedRequests().size(), "取消后应可继续新对话");
            assertTrue(String.join("\n", output.lines()).contains("新回合回答"),
                    "新 exchange 应正常生成");
        } finally {
            ExchangeRunner.awaitLoopEndTimeoutMillis = saved;
        }
    }

    // ---- P1-8 会话持久化与 plan 交付渲染 ----

    /** 会话落盘已改为"消息进历史即追加"：空会话不建文件，非空会话逐条写进项目级会话目录 */
    @Test
    void sessionFileIsWrittenAsMessagesArrive() throws Exception {
        Path projectRoot = tempDir.resolve("proj");
        Files.createDirectories(projectRoot);
        Path sessionsDir = projectRoot.resolve(".acode").resolve("sessions");

        ConversationController controller = new ConversationController(
                FakeProvider.scripted(List.of(List.of(FakeProvider.delta("回答"), FakeProvider.complete()))),
                config(), false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());

        assertFalse(Files.exists(sessionsDir), "空会话不应创建任何会话文件");

        controller.handleExchange("你好", () -> false, () -> { });

        assertTrue(Files.isDirectory(sessionsDir), "消息落盘应创建项目级会话目录");
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> listed = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
            assertEquals(1, listed.size(), "一个会话一个文件");
            assertEquals(2, Files.readAllLines(listed.get(0)).size(),
                    "用户消息与助手回复各占一行");
        }
    }

    /** planMode 仅由 mainLoop 的 /plan 命令置位（无终端不可达），测试经反射置位 */
    @Test
    void planDeliveredLoopCompletionPrintsPlanBody() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("计划：重构 X"),
                        FakeProvider.toolUse("id-1", "ExitPlanMode", JSON.createObjectNode()),
                        FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        Field planMode = ConversationController.class.getDeclaredField("planMode");
        planMode.setAccessible(true);
        planMode.setBoolean(controller, true);

        controller.handleExchange("做个计划", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("（计划已交付）"), "应输出计划交付提示：" + joined);
        assertTrue(joined.contains("计划：重构 X"), "计划正文应打印进回滚：" + joined);
        assertTrue(joined.contains("输入 /do 退出 plan 模式开始执行"), "应提示退出 plan 模式");
        try (Stream<Path> files = Files.list(tempDir.resolve(".acode/plans"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("plan-")),
                    "计划应落盘到 .acode/plans/");
        }
    }

    // ---- P2-14 RetryEvent / ErrorEvent 渲染 ----

    @Test
    void retryAndErrorEventsRenderInOutput() throws Exception {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.error(new RateLimitException("限流了"))),
                List.of(FakeProvider.error(new InvalidRequestException("参数错误")))));
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(tempDir);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);
        controller.handleExchange("触发重试", () -> false, () -> { });

        String joined = String.join("\n", output.lines());
        assertTrue(joined.contains("（重试中：限流了）"), "RetryEvent 应渲染重试行：" + joined);
        assertTrue(joined.contains("（错误：参数错误）"), "ErrorEvent 应渲染错误行：" + joined);
        assertTrue(sw.toString().contains("（重试中：限流了）"), "重试行应经活跃区写屏：" + sw);
    }

    // ---- P2-15 permissionChecker 懒构建与 config 初始模式 ----

    @Test
    void permissionModeLazilyBuildsChecker() {
        // 不注入 checker：切档应触发懒构建（buildPermissionChecker），而非依赖测试替身
        ConversationController controller =
                new ConversationController(FakeProvider.scripted(List.of()), config(), false);
        controller.setProjectRoot(tempDir);
        controller.setOutput(new OutputPane());
        StringWriter sw = new StringWriter();
        controller.setScreenWriter(sw);

        controller.handlePermissionMode("acceptEdits", new LiveRegionRenderer(80, 24), sw);
        assertTrue(sw.toString().contains("已切换到权限模式：acceptEdits"),
                "未注入 checker 时应懒构建并切档：" + sw);

        StringWriter query = new StringWriter();
        controller.handlePermissionMode("", new LiveRegionRenderer(80, 24), query);
        assertTrue(query.toString().contains("当前权限模式：acceptEdits"),
                "切档后查询应反映真实 checker 模式：" + query);
    }

    @Test
    void configuredPermissionModeAppliesToChecker() throws Exception {
        AppConfig config = config();
        config.setPermissionMode("acceptEdits"); // config 初始模式应继承到懒构建的 checker
        Path target = tempDir.resolve("out.txt");
        AtomicInteger asks = new AtomicInteger();
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "WriteFile",
                                JSON.createObjectNode().put("file_path", target.toString()).put("content", "hi")),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("已写入"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config, false);
        controller.setProjectRoot(tempDir);
        controller.setConfirmAnswerer(event -> {
            asks.incrementAndGet();
            return PermissionResponse.DENY;
        });
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.handleExchange("写 out.txt", () -> false, () -> { });

        assertEquals(0, asks.get(), "acceptEdits 模式下写文件不应弹确认");
        assertEquals("hi", Files.readString(target), "acceptEdits 模式应直接放行写入");
    }
}
