package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.session.SessionCodec;
import com.acode.session.SessionStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T12 生命周期：退出/重开、/clear、加载另一会话的续写归属，以及 clear 联动记忆提取运行态。 */
class SessionLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        config.setMaxIterations(5);
        // 默认关自动提取；只有需要观察提取运行态的那个用例显式打开
        config.setMemoryAuto(false);
        return config;
    }

    private static AppConfig configWithAutoExtraction() {
        AppConfig config = config();
        config.setMemoryAuto(true);
        return config;
    }

    private ConversationController controller(ChatProvider provider, boolean resume) {
        return controller(provider, resume, config());
    }

    private ConversationController controller(ChatProvider provider, boolean resume, AppConfig config) {
        ConversationController controller = new ConversationController(provider, config, resume);
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

    /** 连续多轮各自不同回复（脚本按调用次序消耗） */
    private static FakeProvider turns(String... replies) {
        return FakeProvider.scripted(List.of(replies).stream()
                .map(reply -> List.of(FakeProvider.delta(reply), FakeProvider.complete()))
                .toList());
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

    private long lineCount(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).count();
    }

    @Test
    void historySurvivesExitAndRestart() throws IOException {
        ConversationController first = controller(turn("回答一"), false);
        first.handleExchange("第一问", () -> false, () -> { });
        first.closeSession();

        Path file = sessionFiles().get(0);
        assertEquals(2, lineCount(file), "一轮对话落两行");

        ConversationController restarted = controller(turn("回答二"), true);
        restarted.restoreIfResume();
        assertEquals(2, restarted.conversation().messageCount(), "重开应读回上一轮历史");

        restarted.handleExchange("第二问", () -> false, () -> { });
        restarted.closeSession();

        assertEquals(1, sessionFiles().size(), "续写不应新建文件");
        assertEquals(4, lineCount(sessionFiles().get(0)), "新消息追加进同一文件");
    }

    @Test
    void clearKeepsAppendingToTheSameSessionFile() throws IOException {
        ConversationController controller = controller(turns("回答一", "回答二"), false);
        controller.handleExchange("第一问", () -> false, () -> { });

        controller.conversation().clear(); // /clear
        controller.handleExchange("第二问", () -> false, () -> { });
        controller.closeSession();

        assertEquals(1, sessionFiles().size(), "/clear 不应新建会话文件");
        assertEquals(4, lineCount(sessionFiles().get(0)), "清空后新消息仍追加进同一文件");
        assertEquals(2, controller.conversation().messageCount(), "/clear 只清内存历史");
    }

    @Test
    void resumingTheLatestSessionContinuesWritingIntoIt() throws IOException {
        writeSession("20260101-000000-old1", 100L, "旧会话内容");
        Path older = sessionsDir().resolve("20260101-000000-old1.jsonl");
        long olderLines = lineCount(older);

        writeSession("20260101-000001-new1", 200L, "较新会话内容");

        ConversationController controller = controller(turn("回答"), true);
        controller.restoreIfResume();
        assertEquals(1, lineCount(sessionsDir().resolve("20260101-000001-new1.jsonl")),
                "恢复的是最近活跃的会话");

        controller.handleExchange("追问", () -> false, () -> { });

        assertEquals(3, lineCount(sessionsDir().resolve("20260101-000001-new1.jsonl")),
                "新消息应追加进被恢复的会话文件");
        assertEquals(olderLines, lineCount(older), "未被选中的会话文件不应被改动");
    }

    private void writeSession(String id, long ts, String content) throws IOException {
        Files.createDirectories(sessionsDir());
        Files.writeString(sessionsDir().resolve(id + ".jsonl"),
                SessionCodec.encode(ChatMessage.of(USER, content), ts) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void clearResetsMemoryExtractionState() throws Exception {
        BlockingExtractionProvider provider = new BlockingExtractionProvider();
        ConversationController controller = controller(provider, false, configWithAutoExtraction());

        controller.handleExchange("第一问", () -> false, () -> { });
        assertTrue(provider.extractionEntered.await(5, TimeUnit.SECONDS), "应触发一次提取");
        assertTrue(controller.memoryManager().scheduler().isRunning(), "提取任务在跑");

        controller.conversation().clear(); // /clear 走清除钩子

        assertFalse(controller.memoryManager().scheduler().isRunning(),
                "/clear 应重置记忆提取运行态");
        provider.release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (controller.memoryManager().scheduler().completedRuns() < 1
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, controller.memoryManager().scheduler().completedRuns(), "提取任务应已收尾");
        assertTrue(controller.memoryManager().store().listAll().isEmpty(),
                "已被 clear 作废的那一轮不得写回");
    }

    @Test
    void loadingASessionResetsMemoryExtractionState() throws Exception {
        writeSession("20260101-000000-old1", 100L, "旧会话内容");
        BlockingExtractionProvider provider = new BlockingExtractionProvider();
        ConversationController controller = controller(provider, true, configWithAutoExtraction());
        controller.restoreIfResume(); // 加载会话

        controller.handleExchange("第一问", () -> false, () -> { });
        assertTrue(provider.extractionEntered.await(5, TimeUnit.SECONDS), "应触发一次提取");
        assertTrue(controller.memoryManager().scheduler().isRunning(), "提取任务在跑");

        controller.restoreIfResume(); // 再次加载会话 → 作废进行中的那一轮

        assertFalse(controller.memoryManager().scheduler().isRunning(),
                "加载会话应重置记忆提取运行态（与 /clear 同一清除钩子）");
        provider.release.countDown();
        awaitCompletedRuns(controller, 1);
        assertTrue(controller.memoryManager().store().listAll().isEmpty(),
                "被加载会话作废的那一轮不得写回");
    }

    @Test
    void extractionFiresOnceAfterANaturalEndWithToolRoundTrips() throws Exception {
        Path file = projectRoot.resolve("a.txt");
        Files.writeString(file, "内容", StandardCharsets.UTF_8);
        FakeProvider provider = FakeProvider.scripted(List.of(
                List.of(FakeProvider.toolUse("id-1", "ReadFile",
                                JSON.createObjectNode().put("file_path", file.toString())),
                        FakeProvider.complete()),
                List.of(FakeProvider.delta("最终回答"), FakeProvider.complete()),
                List.of(FakeProvider.delta("[]"), FakeProvider.complete())));
        ConversationController controller = controller(provider, false, configWithAutoExtraction());

        controller.handleExchange("读一下 a.txt", () -> false, () -> { });
        awaitCompletedRuns(controller, 1);

        long chatCalls = provider.receivedRequests().stream()
                .filter(r -> !r.tools().isEmpty()).count();
        long extractionCalls = provider.receivedRequests().stream()
                .filter(r -> r.tools().isEmpty()).count();
        assertEquals(2, chatCalls, "工具往返 + 最终回复共两轮对话请求");
        assertEquals(1, extractionCalls, "工具往返中途不触发，整轮自然结束才触发一次");
    }

    private static void awaitCompletedRuns(ConversationController controller, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (controller.memoryManager().scheduler().completedRuns() < expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, controller.memoryManager().scheduler().completedRuns(),
                "提取任务应已收尾");
    }

    /** 对话请求正常应答；提取请求（无工具）阻塞，便于断言运行态与 clear 联动 */
    private static final class BlockingExtractionProvider implements ChatProvider {
        final CountDownLatch extractionEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void streamChat(ChatRequest request, ChatListener listener) {
            if (!request.tools().isEmpty()) {
                listener.onDelta("回答");
                listener.onComplete();
                return;
            }
            extractionEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            listener.onDelta("[]");
            listener.onComplete();
        }
    }
}
