package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ContentBlock;
import com.acode.provider.FakeProvider;
import com.acode.provider.ToolUseBlock;
import com.acode.session.SessionCodec;
import com.acode.ui.OutputPane;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T13 端到端：ACODE.md 展开 → 注入 → 会话追加/压缩重写/恢复续写 → 异步提取 → 索引注入。
 * 全部走真链路，只有 provider 是本地假实现（无外部网络）。
 */
class MemorySystemEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EXTRACT_ONE = """
            [{"op":"create","type":"project","name":"build-env","description":"构建需 JDK21",
              "body":"**Why**：release 21\\n**How to apply**：设 JAVA_HOME"}]
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

    private static AppConfig config(int maxContextTokens) {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(maxContextTokens);
        config.setMaxIterations(5);
        return config;
    }

    private ConversationController controller(ChatProvider provider, boolean resume, int window) {
        ConversationController controller = new ConversationController(provider, config(window), resume);
        controller.setProjectRoot(projectRoot);
        controller.setOutput(new OutputPane());
        controller.setScreenWriter(new StringWriter());
        controller.initSessionState();
        return controller;
    }

    private static FakeProvider turn(String reply) {
        return FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta(reply), FakeProvider.complete())));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Path sessionsDir() {
        return projectRoot.resolve(".acode").resolve("sessions");
    }

    private List<Path> sessionFiles() throws IOException {
        if (!Files.isDirectory(sessionsDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
        }
    }

    private static long lineCount(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank()).count();
    }

    private static String systemContentOf(FakeProvider provider) {
        return provider.receivedRequests().stream()
                .flatMap(request -> request.messages().stream())
                .filter(m -> m.role() == ChatMessage.Role.SYSTEM)
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
    }

    @Test
    void threeLayerInstructionsAreInjectedAndEscapesSkipped() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "TEAM_RULE\n@include rules.md\n@include ../escaped.md");
        write(projectRoot.resolve("rules.md"), "INCLUDED_RULE");
        write(projectRoot.resolve(".acode").resolve("ACODE.md"), "LOCAL_RULE");
        write(fakeHome.resolve(".acode").resolve("ACODE.md"), "USER_RULE");

        FakeProvider provider = turn("回答");
        ConversationController controller = controller(provider, false, 200_000);
        controller.handleExchange("你好", () -> false, () -> { });

        String system = systemContentOf(provider);
        int team = system.indexOf("TEAM_RULE");
        int local = system.indexOf("LOCAL_RULE");
        int user = system.indexOf("USER_RULE");
        assertTrue(team >= 0 && local >= 0 && user >= 0, system);
        assertTrue(team < local && local < user, "三层顺序：项目根 → 项目 .acode → 用户主目录");
        assertTrue(system.contains("INCLUDED_RULE"), "@include 应被原地展开");
        assertFalse(system.contains("escaped"), "越界引用不得进入提示");
    }

    @Test
    void sessionGrowsThenIsRewrittenOnCompactionAndResumesInPlace() throws IOException {
        Files.createDirectories(sessionsDir());
        Path file = sessionsDir().resolve("20260101-000000-abcd.jsonl");
        long ts = System.currentTimeMillis() / 1000;
        Files.writeString(file, SessionCodec.encode(ChatMessage.of(USER, "x".repeat(240_000)), ts)
                + "\n" + SessionCodec.encode(ChatMessage.of(USER, "原始问题"), ts) + "\n",
                StandardCharsets.UTF_8);
        assertEquals(2, lineCount(file));

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("<summary>恢复摘要</summary>"), FakeProvider.complete()),
                List.of(FakeProvider.delta("回答"), FakeProvider.complete())));
        ConversationController controller = controller(provider, true, 80_000);
        controller.restoreIfResume();

        int rebuilt = controller.conversation().messageCount();
        assertEquals(rebuilt, lineCount(file), "压缩后会话文件被整段重写为重建后历史");
        assertTrue(Files.size(file) < 240_000, "重写后不应残留超长原文");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("恢复摘要"));

        controller.handleExchange("继续", () -> false, () -> { });

        assertEquals(1, sessionFiles().size(), "续写不应新建文件");
        assertEquals(controller.conversation().messageCount(), lineCount(file),
                "后续消息追加进被恢复的同一文件");
    }

    @Test
    void danglingToolCallIsTruncatedSoTheNextRequestSucceeds() throws IOException {
        Files.createDirectories(sessionsDir());
        Path file = sessionsDir().resolve("20260101-000000-abcd.jsonl");
        long ts = System.currentTimeMillis() / 1000;
        ChatMessage dangling = new ChatMessage(ASSISTANT, List.of(
                new ToolUseBlock("dangling", "ReadFile", JSON.createObjectNode())));
        Files.writeString(file, SessionCodec.encode(ChatMessage.of(USER, "上次的问题"), ts) + "\n"
                + SessionCodec.encode(ChatMessage.of(ASSISTANT, "上次的回答"), ts) + "\n"
                + SessionCodec.encode(dangling, ts) + "\n", StandardCharsets.UTF_8);

        FakeProvider provider = turn("继续回答");
        ConversationController controller = controller(provider, true, 200_000);
        controller.restoreIfResume();

        for (ChatMessage message : controller.conversation().history()) {
            for (ContentBlock block : message.blocks()) {
                assertFalse(block instanceof ToolUseBlock, "恢复历史不应残留悬空 tool_use");
            }
        }

        controller.handleExchange("继续", () -> false, () -> { });
        assertTrue(provider.receivedRequests().stream().anyMatch(r -> !r.tools().isEmpty()),
                "截断后请求可正常发出");
    }

    @Test
    void extractionWritesMemoriesThatTheNextSessionInjects() throws IOException {
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete()),
                List.of(FakeProvider.delta(EXTRACT_ONE), FakeProvider.complete())));
        ConversationController controller = controller(provider, false, 200_000);
        controller.handleExchange("我们项目要用 JDK21 构建", () -> false, () -> { });
        awaitExtraction(controller);

        Path memoryDir = projectRoot.resolve(".acode").resolve("memory");
        assertTrue(Files.isRegularFile(memoryDir.resolve("project-build-env.md")),
                "项目类记忆应落在项目级记忆根");
        String index = Files.readString(memoryDir.resolve("MEMORY.md"), StandardCharsets.UTF_8);
        assertEquals("- [build-env](project-build-env.md) - 构建需 JDK21\n", index);

        // 下个会话：索引随 system 提示注入
        FakeProvider next = turn("回答");
        ConversationController fresh = controller(next, false, 200_000);
        fresh.initSessionState();
        fresh.handleExchange("再问一句", () -> false, () -> { });

        String system = systemContentOf(next);
        assertTrue(system.contains("# Memory index"), system);
        assertTrue(system.contains("project-build-env.md"), "新记忆应在下个会话的索引里");
        assertFalse(system.contains("# Project instructions"), "三层 ACODE.md 全缺失时不出项目指令段");
    }

    @Test
    void nothingIsWrittenOutsideTheAcodesNamespaces() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "@include rules.md");
        write(projectRoot.resolve("rules.md"), "TEAM_RULE");
        Set<Path> before = filesUnder(tempDir);

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete()),
                List.of(FakeProvider.delta(EXTRACT_ONE), FakeProvider.complete())));
        ConversationController controller = controller(provider, false, 200_000);
        controller.handleExchange("我们项目要用 JDK21 构建", () -> false, () -> { });
        awaitExtraction(controller);
        controller.closeSession();

        Set<Path> added = new TreeSet<>(filesUnder(tempDir));
        added.removeAll(before);
        assertFalse(added.isEmpty(), "本轮应产生会话文件与记忆文件，否则本断言没有意义");
        for (Path file : added) {
            assertTrue(file.startsWith(projectRoot.resolve(".acode"))
                            || file.startsWith(fakeHome.resolve(".acode")),
                    "写入越界（只允许 <项目根>/.acode 与 ~/.acode）：" + file);
        }
        assertFalse(Files.exists(projectRoot.resolve(".gitignore")), "不得自动改用户 .gitignore");
        assertFalse(Files.exists(fakeHome.resolve(".gitignore")));
    }

    @Test
    void instructionWarningsArePrintedAndDoNotBlockTheExchange() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "TEAM_RULE\n@include ../escaped.md");
        write(tempDir.resolve("escaped.md"), "ESCAPED_BODY");

        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.delta("回答"), FakeProvider.complete()),
                List.of(FakeProvider.delta("[]"), FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config(200_000), false);
        controller.setProjectRoot(projectRoot);
        OutputPane output = new OutputPane();
        controller.setOutput(output);
        controller.setScreenWriter(new StringWriter());
        controller.initSessionState();

        assertTrue(output.lines().stream().anyMatch(line -> line.contains("超出项目范围")),
                "越界引用应输出一行告警：" + output.lines());
        controller.handleExchange("你好", () -> false, () -> { });
        assertTrue(provider.receivedRequests().stream().anyMatch(r -> !r.tools().isEmpty()),
                "启动告警不得阻断对话");
    }

    private static Set<Path> filesUnder(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static void awaitExtraction(ConversationController controller) throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (controller.memoryManager().scheduler().completedRuns() < 1
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待提取收尾被中断", e);
            }
        }
        assertEquals(1, controller.memoryManager().scheduler().completedRuns(),
                "一轮对话结束后应完成一次提取");
    }
}
