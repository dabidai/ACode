package com.acode.agent;

import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    /** 记录开始/结束时序的桩工具：进入时记 start、countDown entered；可选阻塞等待 release 后再记 done。 */
    private static class RecordingTool implements Tool {
        final String name;
        final Permission permission;
        final List<String> log;
        final CountDownLatch entered;
        final CountDownLatch release;

        RecordingTool(String name, Permission permission, List<String> log) {
            this(name, permission, log, null, null);
        }

        RecordingTool(String name, Permission permission, List<String> log,
                      CountDownLatch entered, CountDownLatch release) {
            this.name = name;
            this.permission = permission;
            this.log = log;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String description() {
            return "test stub";
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Permission permission() {
            return permission;
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode input, ToolContext context) {
            synchronized (log) {
                log.add(name + "_start");
            }
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (log) {
                log.add(name + "_done");
            }
            return ToolResult.success(name + "-output");
        }
    }

    private static ToolRegistry registry(Tool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static StreamingToolExecutor executor(ToolRegistry registry) {
        return new StreamingToolExecutor(registry, new ToolContext(java.nio.file.Path.of(".")));
    }

    private static StreamingToolExecutor executor(ToolRegistry registry, ConfirmationGate gate) {
        return new StreamingToolExecutor(registry, new ToolContext(java.nio.file.Path.of(".")), gate);
    }

    private static final ConfirmationGate APPROVE = (call, events, cancelled) -> true;
    private static final ConfirmationGate DENY = (call, events, cancelled) -> false;

    @Test
    void mixedBatchRunsReadsBeforeWriteAndKeepsSerialOrder() throws Exception {
        List<String> log = new ArrayList<>();
        CountDownLatch readsEntered = new CountDownLatch(2);
        CountDownLatch releaseReads = new CountDownLatch(1);
        Tool readA = new RecordingTool("ReadA", Permission.READ, log, readsEntered, releaseReads);
        Tool readB = new RecordingTool("ReadB", Permission.READ, log, readsEntered, releaseReads);
        Tool write1 = new RecordingTool("Write1", Permission.WRITE, log);
        Tool write2 = new RecordingTool("Write2", Permission.WRITE, log);

        BlockingQueue<AgentEvent> events = queue();
        List<ToolUseBlock> calls = List.of(call("idA", "ReadA"), call("idW1", "Write1"),
                call("idB", "ReadB"), call("idW2", "Write2"));

        StreamingToolExecutor executor = executor(registry(readA, readB, write1, write2));
        java.util.concurrent.Future<List<ToolResult>> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> executor.execute(calls, events, new AtomicBoolean(false)));

        assertTrue(readsEntered.await(2, TimeUnit.SECONDS), "两个读工具应同时进入");
        releaseReads.countDown();
        List<ToolResult> results = future.get(5, TimeUnit.SECONDS);

        // 执行时序：两个读先完成，写按声明顺序在其后
        int readADone = log.indexOf("ReadA_done");
        int readBDone = log.indexOf("ReadB_done");
        int write1Start = log.indexOf("Write1_start");
        int write2Start = log.indexOf("Write2_start");
        assertTrue(readADone >= 0 && readBDone >= 0 && write1Start >= 0 && write2Start >= 0);
        assertTrue(readADone < write1Start, "读 A 应先于写完成");
        assertTrue(readBDone < write1Start, "读 B 应先于写完成");
        assertTrue(write1Start < write2Start, "写类应保持声明顺序串行");

        // 结果对齐声明顺序
        assertEquals(4, results.size());
        assertEquals("ReadA-output", results.get(0).output());
        assertEquals("Write1-output", results.get(1).output());
        assertEquals("ReadB-output", results.get(2).output());
        assertEquals("Write2-output", results.get(3).output());
    }

    @Test
    void twoReadsRunConcurrently() throws Exception {
        List<String> log = new ArrayList<>();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Tool read1 = new RecordingTool("Read1", Permission.READ, log, entered, release);
        Tool read2 = new RecordingTool("Read2", Permission.READ, log, entered, release);

        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(read1, read2));
        java.util.concurrent.Future<List<ToolResult>> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> executor.execute(List.of(call("r1", "Read1"), call("r2", "Read2")),
                        events, new AtomicBoolean(false)));

        assertTrue(entered.await(2, TimeUnit.SECONDS), "两个读应同时运行（真实并发）");
        release.countDown();
        List<ToolResult> results = future.get(5, TimeUnit.SECONDS);
        assertEquals(2, results.size());
        assertFalse(results.get(0).isError());
        assertFalse(results.get(1).isError());
    }

    @Test
    void toolResultEventEmittedPerCompletedCall() throws Exception {
        List<String> log = new ArrayList<>();
        Tool read = new RecordingTool("Read", Permission.READ, log);
        Tool write = new RecordingTool("Write", Permission.WRITE, log);

        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(read, write));
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Read"), call("id2", "Write")), events, new AtomicBoolean(false));

        assertEquals(2, results.size());
        List<AgentEvent> list = new ArrayList<>();
        events.drainTo(list);
        assertEquals(2, list.size());
        ToolResultEvent e1 = (ToolResultEvent) list.get(0);
        assertEquals("id1", e1.toolId());
        assertEquals("Read", e1.toolName());
        assertEquals("Read-output", e1.output());
        assertFalse(e1.isError());
        ToolResultEvent e2 = (ToolResultEvent) list.get(1);
        assertEquals("Write", e2.toolName());
    }

    @Test
    void cancelledFillsPlaceholderForPendingCalls() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        Tool read = new RecordingTool("Read", Permission.READ, new ArrayList<>());
        Tool write = new RecordingTool("Write", Permission.WRITE, new ArrayList<>(),
                writeEntered, releaseWrite);

        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StreamingToolExecutor executor = executor(registry(read, write));
        java.util.concurrent.Future<List<ToolResult>> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> executor.execute(List.of(call("id1", "Read"), call("id2", "Write")),
                        events, cancelled));

        assertTrue(writeEntered.await(2, TimeUnit.SECONDS), "写工具应已开始");
        cancelled.set(true);
        releaseWrite.countDown();
        List<ToolResult> results = future.get(5, TimeUnit.SECONDS);

        assertEquals(2, results.size(), "取消后结果长度与输入一致");
        assertFalse(results.get(0).isError(), "已完成的读保留真实结果");
        assertTrue(results.get(1).isError(), "未完成的写补「已取消」");
        assertEquals("已取消", results.get(1).errorMessage());
    }

    @Test
    void unregisteredToolReturnsFailureWithoutException() {
        BlockingQueue<AgentEvent> events = queue();
        Tool read = new RecordingTool("Read", Permission.READ, new ArrayList<>());
        StreamingToolExecutor executor = executor(registry(read));
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Nope")), events, new AtomicBoolean(false));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().contains("未注册"));
    }

    @Test
    void emptyBatchReturnsEmptyList() {
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry());
        List<ToolResult> results = executor.execute(List.of(), events, new AtomicBoolean(false));

        assertTrue(results.isEmpty());
        List<AgentEvent> list = new ArrayList<>();
        events.drainTo(list);
        assertTrue(list.isEmpty(), "空批次不产生事件");
    }

    // ---- 确认门槛（T3） ----

    @Test
    void writeToolRunsWhenGateApproves() {
        List<String> log = new ArrayList<>();
        Tool write = new RecordingTool("Write", Permission.WRITE, log);
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(write), APPROVE);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Write")), events, new AtomicBoolean(false));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isError());
        assertEquals("Write-output", results.get(0).output());
        assertTrue(log.contains("Write_start"), "批准后工具应执行");
    }

    @Test
    void writeToolNotExecutedAndFailureWhenGateDenies() {
        List<String> log = new ArrayList<>();
        Tool write = new RecordingTool("Write", Permission.WRITE, log);
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(write), DENY);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Write")), events, new AtomicBoolean(false));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().contains("拒绝"));
        assertFalse(log.contains("Write_start"), "拒绝后工具不应执行");
        List<AgentEvent> list = new ArrayList<>();
        events.drainTo(list);
        assertEquals(1, list.size(), "拒绝也发一条 ToolResultEvent 供 UI 渲染");
        ToolResultEvent e = (ToolResultEvent) list.get(0);
        assertTrue(e.isError());
        assertTrue(e.output().contains("拒绝"));
    }

    @Test
    void readToolSkipsConfirmationGate() {
        AtomicBoolean gateCalled = new AtomicBoolean(false);
        ConfirmationGate trackingGate = (call, events, cancelled) -> {
            gateCalled.set(true);
            return false;
        };
        Tool read = new RecordingTool("Read", Permission.READ, new ArrayList<>());
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(read), trackingGate);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Read")), events, new AtomicBoolean(false));

        assertFalse(results.get(0).isError(), "READ 工具应正常执行");
        assertFalse(gateCalled.get(), "READ 工具不应触发确认门槛");
    }

    @Test
    void gateDeniedUnderCancellationReturnsFailureWithoutHang() throws Exception {
        List<String> log = new ArrayList<>();
        Tool write = new RecordingTool("Write", Permission.WRITE, log);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 模拟 EventConfirmationGate：取消置位前阻塞等待，取消后确认失败
        ConfirmationGate cancelAwareGate = (call, events, cancelledFlag) -> {
            while (!cancelledFlag.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        };
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(write), cancelAwareGate);
        java.util.concurrent.Future<List<ToolResult>> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> executor.execute(List.of(call("id1", "Write")), events, cancelled));

        Thread.sleep(100);
        cancelled.set(true);
        List<ToolResult> results = future.get(2, TimeUnit.SECONDS);
        assertTrue(results.get(0).isError(), "取消置位后确认失败，返回拒绝结果");
        assertFalse(log.contains("Write_start"), "确认被取消拦截，工具不应执行");
    }
}
