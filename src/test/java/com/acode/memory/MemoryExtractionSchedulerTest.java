package com.acode.memory;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatListener;
import com.acode.provider.ChatMessage;
import com.acode.provider.ChatProvider;
import com.acode.provider.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionSchedulerTest {

    @TempDir
    Path tempDir;

    private static final String ONE_CREATE = """
            [{"op":"create","type":"user","name":"pref","description":"摘要","body":"正文"}]
            """;

    private MemoryScope project;
    private MemoryScope user;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        project = MemoryScope.project(tempDir.resolve("proj"));
        user = MemoryScope.user(tempDir.resolve("home"));
        store = new MemoryStore(project, user);
    }

    private MemoryExtractionScheduler scheduler(ChatProvider provider) {
        Conversation conversation = new Conversation("m", false, 4096, 200_000);
        conversation.addMessage(ChatMessage.of(USER, "问"));
        return new MemoryExtractionScheduler(new MemoryExtractor(provider, store, conversation));
    }

    /** 可控阻塞的假 provider：进入 streamChat 后等 release，便于断言"运行中"的并发行为 */
    private static final class BlockingProvider implements ChatProvider {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private final String reply;

        BlockingProvider(String reply) {
            this.reply = reply;
        }

        @Override
        public void streamChat(ChatRequest request, ChatListener listener) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            listener.onDelta(reply);
            listener.onComplete();
        }
    }

    @Test
    void triggerWhileRunningIsSkipped() throws Exception {
        BlockingProvider provider = new BlockingProvider("[]");
        MemoryExtractionScheduler scheduler = scheduler(provider);

        scheduler.triggerAsync();
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS), "首个任务应进入 provider");
        scheduler.triggerAsync(); // 运行中再次触发 → 本轮跳过（不排队、不累计）

        provider.release.countDown();
        assertTrue(scheduler.awaitIdle(5000));
        assertEquals(1, provider.calls.get(), "运行中再次触发不得产生第二次提取调用");
        assertEquals(1, scheduler.completedRuns());
    }

    @Test
    void appliesResultWhenGenerationIsStillCurrent() throws Exception {
        BlockingProvider provider = new BlockingProvider(ONE_CREATE);
        MemoryExtractionScheduler scheduler = scheduler(provider);

        scheduler.triggerAsync();
        provider.release.countDown();
        assertTrue(scheduler.awaitIdle(5000));

        assertTrue(store.read("user-pref.md").isPresent(), "代次未失效时结果应写回");
        assertEquals(1, scheduler.lastOutcome().created());
    }

    @Test
    void staleGenerationDiscardsTheWholeRound() throws Exception {
        BlockingProvider provider = new BlockingProvider(ONE_CREATE);
        MemoryExtractionScheduler scheduler = scheduler(provider);

        scheduler.triggerAsync();
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        scheduler.reset(); // /clear 或加载会话：作废这一轮
        provider.release.countDown();
        assertTrue(scheduler.awaitIdle(5000));

        assertTrue(store.listAll().isEmpty(), "代次失效的那一轮必须零写入");
    }

    @Test
    void resetClearsTheRunningState() throws Exception {
        BlockingProvider provider = new BlockingProvider("[]");
        MemoryExtractionScheduler scheduler = scheduler(provider);

        scheduler.triggerAsync();
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        scheduler.reset();
        assertFalse(scheduler.isRunning(), "reset 后不应被旧的运行中状态拦住");

        scheduler.triggerAsync();
        assertEquals(2, awaitCalls(provider, 2), "reset 后应立即能再次触发");
        provider.release.countDown();
        assertTrue(scheduler.awaitIdle(5000));
    }

    private static int awaitCalls(BlockingProvider provider, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (provider.calls.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        return provider.calls.get();
    }

    @Test
    void extractorExceptionDoesNotPropagateOrBlockLaterRounds() throws Exception {
        ChatProvider throwing = (request, listener) -> {
            throw new IllegalStateException("boom");
        };
        MemoryExtractionScheduler scheduler = scheduler(throwing);

        scheduler.triggerAsync();
        assertTrue(scheduler.awaitIdle(5000), "异常不应让调度卡死");
        assertEquals(1, scheduler.completedRuns());

        scheduler.triggerAsync();
        assertTrue(scheduler.awaitIdle(5000), "失败不设熔断：下轮照常再试");
        assertEquals(2, scheduler.completedRuns());
    }

    @Test
    void runNowIsSynchronousAndReturnsTheOutcome() {
        MemoryExtractionScheduler scheduler = scheduler((request, listener) -> {
            listener.onDelta(ONE_CREATE);
            listener.onComplete();
        });

        MemoryExtractor.Outcome outcome = scheduler.runNow();

        assertEquals(1, outcome.created());
        assertFalse(outcome.failed());
        assertTrue(store.read("user-pref.md").isPresent());
    }

    @Test
    void runNowReportsFailureWhenReplyIsUnparsable() {
        MemoryExtractionScheduler scheduler = scheduler((request, listener) -> {
            listener.onDelta("无法解析");
            listener.onComplete();
        });

        assertTrue(scheduler.runNow().failed());
        assertTrue(store.listAll().isEmpty());
    }
}
