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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆系统的控制器级集成验证（ch08 遗留 + ch09 迁移后保留）：
 * 提取触发入口已由 /memory 命令接管（见 command/MemoryCommandTest），本组只用
 * {@code memoryManager().extractNow()} 驱动提取，断言索引注入与会话内可见性。
 */
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
        controller(FakeProvider.streaming(CREATE_ONE)).memoryManager().extractNow();

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
        controller.memoryManager().extractNow();
        controller.handleExchange("第二问", () -> false, () -> { });

        List<ChatRequest> chatRequests = provider.receivedRequests().stream()
                .filter(r -> !r.tools().isEmpty())
                .toList();
        assertEquals(2, chatRequests.size(), "应有两轮对话请求");
        assertEquals(systemContentOf(chatRequests.get(0)), systemContentOf(chatRequests.get(1)),
                "会话内 system 提示字节不变（新记忆下个会话才生效）");
    }
}
