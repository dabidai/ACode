package com.acode.memory;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatRequest;
import com.acode.provider.FakeProvider;
import com.acode.provider.ProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractorTest {

    @TempDir
    Path tempDir;

    private MemoryScope project;
    private MemoryScope user;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        project = MemoryScope.project(tempDir.resolve("proj"));
        user = MemoryScope.user(tempDir.resolve("home"));
        store = new MemoryStore(project, user);
    }

    private Conversation conversation() {
        return new Conversation("m", false, 4096, 200_000);
    }

    private MemoryExtractor extractor(FakeProvider provider, Conversation conversation) {
        return new MemoryExtractor(provider, store, conversation);
    }

    private static String payload(String json) {
        return "```json\n" + json + "\n```";
    }

    @Test
    void appliesCreateOperationsIntoTheirOwnRoots() {
        Conversation conversation = conversation();
        conversation.addMessage(ChatMessage.of(USER, "以后用 any 代替 interface{}"));
        MemoryExtractor.Outcome outcome = extractor(FakeProvider.streaming(payload("""
                [{"op":"create","type":"user","name":"prefer-any","description":"偏好 any",
                  "body":"**Why**：简单\\n**How to apply**：默认 any"},
                 {"op":"create","type":"project","name":"migrate","description":"迁移工具选型",
                  "body":"**Why**：团队约定"}]
                """)), conversation).run();

        assertEquals(2, outcome.created());
        assertEquals(0, outcome.updated());
        assertTrue(Files.isRegularFile(user.root().resolve("user-prefer-any.md")));
        assertTrue(Files.isRegularFile(project.root().resolve("project-migrate.md")));
        assertTrue(read(user.indexPath()).contains("- [prefer-any](user-prefer-any.md) - 偏好 any"));
        assertTrue(store.injectionText().contains("偏好 any"));
    }

    @Test
    void updateAndDeleteOperationsTakeEffectAndSyncTheIndex() {
        store.write(MemoryType.USER, "prefer-any", "旧摘要", "旧正文");
        store.write(MemoryType.USER, "obsolete", "待删", "正文");

        MemoryExtractor.Outcome outcome = extractor(FakeProvider.streaming(payload("""
                [{"op":"update","type":"user","name":"prefer-any","description":"新摘要","body":"新正文"},
                 {"op":"delete","type":"user","name":"obsolete"}]
                """)), conversation()).run();

        assertEquals(1, outcome.updated());
        assertEquals(1, outcome.deleted());
        assertEquals("新摘要", store.read("user-prefer-any.md").orElseThrow().description());
        assertFalse(Files.exists(user.root().resolve("user-obsolete.md")));
        assertFalse(read(user.indexPath()).contains("obsolete"));
    }

    @Test
    void createOnAnExistingNameCountsAsUpdate() {
        store.write(MemoryType.USER, "prefer-any", "旧摘要", "旧正文");
        MemoryExtractor.Outcome outcome = extractor(FakeProvider.streaming(payload("""
                [{"op":"create","type":"user","name":"prefer-any","description":"新摘要","body":"新正文"}]
                """)), conversation()).run();
        assertEquals(1, outcome.updated());
        assertEquals(0, outcome.created());
    }

    @Test
    void emptyArrayWritesNothing() throws Exception {
        long before = memoryFileCount();
        MemoryExtractor.Outcome outcome = extractor(FakeProvider.streaming("[]"), conversation()).run();

        assertTrue(outcome.nothing());
        assertFalse(outcome.failed());
        assertEquals(before, memoryFileCount());
    }

    @Test
    void unparsableOutputWritesNothing() throws Exception {
        String[] bad = {
                "这不是 JSON",
                "[{\"op\":\"create\",\"type\":\"user\"}]",
                "[{\"op\":\"create\",\"type\":\"mystery\",\"name\":\"x\",\"description\":\"y\"}]",
                "[{\"op\":\"create\",\"type\":\"user\",\"name\":\"..\",\"description\":\"y\"}]",
                "[{\"op\":\"nope\",\"type\":\"user\",\"name\":\"x\",\"description\":\"y\"}]",
                "[{\"op\":\"create\",\"type\":\"user\",\"name\":\"good\",\"description\":\"y\"}, {\"op\":\"create\"}]",
        };
        long before = memoryFileCount();
        for (String raw : bad) {
            MemoryExtractor.Outcome outcome =
                    extractor(FakeProvider.streaming(raw), conversation()).run();
            assertTrue(outcome.failed(), "应判定为整轮放弃：" + raw);
        }
        assertEquals(before, memoryFileCount(), "解析失败必须零文件写入");
    }

    @Test
    void providerFailureWritesNothing() {
        MemoryExtractor.Outcome outcome = extractor(
                FakeProvider.failing(new ProviderException("boom")), conversation()).run();
        assertTrue(outcome.failed());
        assertTrue(store.listAll().isEmpty());
    }

    @Test
    void extractionInstructionCarriesTheDisciplineRules() {
        String instruction = MemoryExtractionPrompt.instruction();

        assertTrue(instruction.contains("Do not create a memory that duplicates an existing one."),
                "提示词必须明确「已有同义记忆不要重复创建」");
        assertTrue(instruction.contains("If nothing is worth remembering, do nothing."),
                "提示词必须明确「没有值得记忆的内容就什么都不做」");
        assertTrue(instruction.contains("Output ONLY a JSON array"),
                "提示词必须要求只输出结构化操作列表");
    }

    @Test
    void extractionMaxTokensMatchesTheDocumentedDefault() {
        assertEquals(4_000, MemoryExtractionPrompt.MAX_TOKENS, "提取请求使用独立常量 max_tokens = 4000");
    }

    @Test
    void extractionInputCarriesIndexExistingMemoriesAndLatestTurn() {
        store.write(MemoryType.USER, "prefer-any", "偏好 any", "正文");
        Conversation conversation = conversation();
        conversation.addMessage(ChatMessage.of(USER, "最近一问"));
        FakeProvider provider = FakeProvider.streaming("[]");

        extractor(provider, conversation).run();

        String input = provider.receivedRequests().get(0).messages().get(1).content();
        assertTrue(input.contains("user-prefer-any.md"), "提取输入应含现有记忆清单：" + input);
        assertTrue(input.contains("偏好 any"), "提取输入应含当前索引文本：" + input);
        assertTrue(input.contains("最近一问"), "提取输入应含最近一轮对话：" + input);
    }

    @Test
    void extractionRequestCarriesNoToolsAndIndependentMaxTokens() {
        Conversation conversation = conversation();
        conversation.addMessage(ChatMessage.of(USER, "问"));
        FakeProvider provider = FakeProvider.streaming("[]");
        extractor(provider, conversation).run();

        ChatRequest request = provider.receivedRequests().get(0);
        assertTrue(request.tools().isEmpty(), "提取请求不携带工具");
        assertFalse(request.thinking(), "提取请求关闭 thinking");
        assertEquals(MemoryExtractionPrompt.MAX_TOKENS, request.maxTokens());
        assertFalse(request.messages().get(0).blocks().isEmpty());
    }

    @Test
    void recentTurnStartsAtTheLastUserTextMessage() {
        Conversation conversation = conversation();
        conversation.addMessage(ChatMessage.of(USER, "第一轮"));
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, "回答"));
        conversation.addMessage(ChatMessage.of(USER, "第二轮"));
        conversation.addMessage(ChatMessage.of(ChatMessage.Role.ASSISTANT, "回答二"));

        List<ChatMessage> turn = extractor(FakeProvider.streaming("[]"), conversation).recentTurn();
        assertEquals(2, turn.size(), "应从最后一条用户文本消息切起");
        assertEquals("第二轮", turn.get(0).content());
    }

    private long memoryFileCount() throws Exception {
        long count = 0;
        for (MemoryScope scope : List.of(project, user)) {
            if (!Files.isDirectory(scope.root())) {
                continue;
            }
            try (Stream<Path> files = Files.list(scope.root())) {
                count += files.filter(p -> p.getFileName().toString().endsWith(".md")).count();
            }
        }
        return count;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
