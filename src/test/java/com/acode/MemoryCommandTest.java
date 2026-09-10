package com.acode;

import com.acode.config.AppConfig;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.memory.MemoryType;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.ui.OutputPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T11 /memory：只读状态输出 + 手动提取入口（关掉自动提取后仍可用）。 */
class MemoryCommandTest {

    private static final String CREATE_ONE = """
            [{"op":"create","type":"user","name":"pref","description":"摘要","body":"正文"}]
            """;

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path fakeHome;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        originalHome = System.getProperty("user.home");
        fakeHome = tempDir.resolve("home");
        projectRoot = tempDir.resolve("proj");
        Files.createDirectories(fakeHome);
        Files.createDirectories(projectRoot);
        System.setProperty("user.home", fakeHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    private static AppConfig config() {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(200_000);
        // 默认关自动提取：本组测试只驱动手动入口，避免异步请求干扰计数
        config.setMemoryAuto(false);
        return config;
    }

    private ConversationController controller(FakeProvider provider) {
        ConversationController controller = new ConversationController(provider, config(), false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());
        controller.initSessionState();
        return controller;
    }

    private Path userMemoryDir() {
        return fakeHome.resolve(".acode").resolve("memory");
    }

    /** 会话实际发出去的 system 内容（首条请求的 SYSTEM 消息） */
    private static String systemContentOf(FakeProvider provider) {
        return systemContentOf(provider.receivedRequests().get(0));
    }

    private static String systemContentOf(ChatRequest request) {
        return request.messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
    }

    @Test
    void statusReportsZeroCountsWhenNothingIsStored() {
        List<String> lines = controller(FakeProvider.streaming("[]")).memoryCommandLines("");

        assertEquals("长期记忆：项目级 0 条 · 用户级 0 条", lines.get(0));
        assertTrue(lines.get(1).startsWith("项目级索引：0 行 / 0 B"), lines.get(1));
        assertTrue(lines.get(2).startsWith("用户级索引：0 行 / 0 B"), lines.get(2));
        assertFalse(lines.get(1).contains("已截断"));
    }

    @Test
    void runReportsCountsInItsOutput() {
        List<String> lines = controller(FakeProvider.streaming(CREATE_ONE)).memoryCommandLines("run");
        assertEquals("记忆提取完成：新增 1 · 更新 0 · 删除 0", lines.get(0));
    }

    @Test
    void runWritesMemoryFileAndIndex() throws IOException {
        controller(FakeProvider.streaming(CREATE_ONE)).handleMemoryCommand("run");

        assertTrue(Files.isRegularFile(userMemoryDir().resolve("user-pref.md")));
        String index = Files.readString(userMemoryDir().resolve("MEMORY.md"), StandardCharsets.UTF_8);
        assertEquals("- [pref](user-pref.md) - 摘要\n", index);
    }

    @Test
    void statusReportsCountsAndIndexStats() {
        controller(FakeProvider.streaming(CREATE_ONE)).handleMemoryCommand("run");

        List<String> lines = controller(FakeProvider.streaming("[]")).memoryCommandLines("");
        assertEquals("长期记忆：项目级 0 条 · 用户级 1 条", lines.get(0));
        assertTrue(lines.get(2).contains("1 行"), lines.get(2));
        assertFalse(lines.get(2).contains("已截断"));
    }

    @Test
    void statusFlagsTruncatedIndex() throws IOException {
        Files.createDirectories(userMemoryDir());
        StringBuilder index = new StringBuilder();
        int over = MemoryStore.INDEX_MAX_LINES + 5;
        for (int i = 0; i < over; i++) {
            String fileName = "user-m" + i + ".md";
            Files.writeString(userMemoryDir().resolve(fileName),
                    "---\nname: m" + i + "\ndescription: d\ntype: user\n---\n\n正文\n",
                    StandardCharsets.UTF_8);
            index.append("- [m").append(i).append("](").append(fileName).append(") - d\n");
        }
        Files.writeString(userMemoryDir().resolve("MEMORY.md"), index.toString(),
                StandardCharsets.UTF_8);

        List<String> lines = controller(FakeProvider.streaming("[]")).memoryCommandLines("");
        assertTrue(lines.get(2).contains("（已截断）"), lines.get(2));
        assertTrue(lines.get(2).contains(String.valueOf(MemoryStore.INDEX_MAX_LINES)), lines.get(2));
    }

    @Test
    void runReportsWhenNothingIsWorthRemembering() {
        List<String> lines = controller(FakeProvider.streaming("[]")).memoryCommandLines("run");
        assertEquals(List.of("（没有值得记忆的内容）"), lines);
        assertFalse(Files.exists(userMemoryDir().resolve("MEMORY.md")));
    }

    @Test
    void runReportsFailureWithoutWritingAnything() {
        List<String> lines = controller(FakeProvider.streaming("完全无法解析")).memoryCommandLines("run");
        assertEquals("记忆提取失败（未写入任何文件）", lines.get(0));
        assertFalse(Files.exists(userMemoryDir()));
    }

    @Test
    void manualRunStillWorksWhenAutoExtractionIsDisabled() {
        AppConfig config = config();
        config.setMemoryAuto(false);
        ConversationController controller = new ConversationController(
                FakeProvider.streaming(CREATE_ONE), config, false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());

        assertEquals("记忆提取完成：新增 1 · 更新 0 · 删除 0",
                controller.memoryCommandLines("run").get(0));
    }

    @Test
    void autoExtractionDisabledMeansNoBackgroundCallAfterAnExchange() {
        AppConfig config = config();
        config.setMemoryAuto(false);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config, false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());

        controller.handleExchange("你好", () -> false, () -> { });

        for (ChatRequest request : provider.receivedRequests()) {
            assertFalse(request.tools().isEmpty(), "关闭自动提取后不应出现无工具的提取请求");
        }
        assertFalse(Files.exists(userMemoryDir()));
    }

    @Test
    void systemPromptCarriesMemoryPointersNotTheirBodies() {
        String bodySentinel = "BODY_TEXT_ONLY_ON_DEMAND_97531";
        new MemoryStore(MemoryScope.project(projectRoot), MemoryScope.user(fakeHome))
                .write(MemoryType.PROJECT, "build-env", "构建需 JDK21", bodySentinel);

        FakeProvider provider = FakeProvider.streaming("回答");
        ConversationController controller = controller(provider);
        controller.initSessionState();
        controller.handleExchange("你好", () -> false, () -> { });

        String system = systemContentOf(provider);
        assertTrue(system.contains("project-build-env.md"), "索引指针应进 system 提示：" + system);
        assertFalse(system.contains(bodySentinel),
                "记忆正文不做自动全文注入（按需由 Agent 自行读文件）");
    }

    @Test
    void asyncExtractionDoesNotChangeTheRunningSessionsSystemPrompt() {
        AppConfig config = config();
        config.setMemoryAuto(true);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答一"), FakeProvider.complete()),
                List.of(FakeProvider.delta(CREATE_ONE), FakeProvider.complete()),
                List.of(FakeProvider.delta("回答二"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config, false);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());
        controller.initSessionState();

        controller.handleExchange("第一问", () -> false, () -> { });
        awaitCompletedExtraction(controller);
        controller.handleExchange("第二问", () -> false, () -> { });

        List<ChatRequest> chatRequests = provider.receivedRequests().stream()
                .filter(r -> !r.tools().isEmpty())
                .toList();
        assertEquals(2, chatRequests.size(), "应有两轮对话请求");
        assertTrue(Files.isRegularFile(userMemoryDir().resolve("user-pref.md")),
                "轮结束后的异步提取应已写入新记忆");
        assertTrue(systemContentOf(chatRequests.get(0)).contains("You are ACode"),
                "system 提示应真实存在（防空串让相等断言空转）");
        assertEquals(systemContentOf(chatRequests.get(0)), systemContentOf(chatRequests.get(1)),
                "会话内 system 提示字节不变：异步写入的新记忆下个会话才生效");
        assertFalse(systemContentOf(chatRequests.get(1)).contains("user-pref.md"),
                "当前会话不得注入本轮新写入的记忆");
    }

    private static void awaitCompletedExtraction(ConversationController controller) {
        long deadline = System.currentTimeMillis() + 5000;
        while (controller.memoryManager().scheduler().completedRuns() < 1
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals(1, controller.memoryManager().scheduler().completedRuns(),
                "一轮对话结束后应完成一次异步提取");
    }

    @Test
    void systemPromptCarriesTheMemoryIndexOnTheNextSession() {
        controller(FakeProvider.streaming(CREATE_ONE)).handleMemoryCommand("run");

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete())));
        ConversationController fresh = controller(provider);
        fresh.initSessionState();
        fresh.handleExchange("你好", () -> false, () -> { });

        String system = systemContentOf(provider);
        assertTrue(system.contains("# Memory index"), system);
        assertTrue(system.contains("user-pref.md"), "索引指针应出现在 system 提示里");
    }

    @Test
    void indexWrittenDuringASessionDoesNotAppearInTheSameSession() {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete()),
                List.of(FakeProvider.delta(CREATE_ONE), FakeProvider.complete()),
                List.of(FakeProvider.delta("回答二"), FakeProvider.complete())));
        ConversationController controller = controller(provider);
        controller.initSessionState();

        controller.handleExchange("第一问", () -> false, () -> { });
        controller.handleMemoryCommand("run");
        controller.handleExchange("第二问", () -> false, () -> { });

        List<ChatRequest> chatRequests = provider.receivedRequests().stream()
                .filter(r -> !r.tools().isEmpty())
                .toList();
        assertEquals(2, chatRequests.size(), "应有两轮对话请求");
        assertEquals(systemContentOf(chatRequests.get(0)), systemContentOf(chatRequests.get(1)),
                "会话内 system 提示字节不变（新记忆下个会话才生效）");
    }
}
