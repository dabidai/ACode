package com.acode.agent;

import com.acode.agent.AgentEvent.ToolResultEvent;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.PermissionResponse;
import com.acode.permission.RuleEngine;
import com.acode.provider.ToolUseBlock;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingToolExecutorTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BlockingQueue<AgentEvent> queue() {
        return new ArrayBlockingQueue<>(AgentEvent.QUEUE_CAPACITY);
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseBlock call(String id, String name, JsonNode args) {
        return new ToolUseBlock(id, name, args);
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
            return ToolResult.success(name + "-output").withDisplay(name + "-display");
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

    private static final ConfirmationGate APPROVE = (call, events, cancelled) -> PermissionResponse.ALLOW;
    private static final ConfirmationGate DENY = (call, events, cancelled) -> PermissionResponse.DENY;

    private PermissionChecker checker(PermissionMode mode) {
        return new PermissionChecker(mode, tempDir, new RuleEngine(
                tempDir.resolve("u.yaml"), tempDir.resolve("p.yaml"), tempDir.resolve("l.yaml")));
    }

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
        assertEquals("Read-display", e1.display(), "普通路径事件 display 应与 result.display() 一致");
        assertTrue(e1.elapsedMs() >= 0, "正常执行应带耗时：" + e1.elapsedMs());
        ToolResultEvent e2 = (ToolResultEvent) list.get(1);
        assertEquals("Write", e2.toolName());
        assertEquals("Write-display", e2.display());
        assertTrue(e2.elapsedMs() >= 0, "正常执行应带耗时：" + e2.elapsedMs());
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
        assertEquals(0, e.elapsedMs(), "拒绝路径耗时记 0（不含确认等待）");
        assertNull(e.display(), "拒绝路径 display 为 null");
    }

    @Test
    void readToolSkipsConfirmationGate() {
        AtomicBoolean gateCalled = new AtomicBoolean(false);
        ConfirmationGate trackingGate = (call, events, cancelled) -> {
            gateCalled.set(true);
            return PermissionResponse.DENY;
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
                    return PermissionResponse.DENY;
                }
            }
            return PermissionResponse.DENY;
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

    // ---- 交互工具（InteractiveTool） ----

    /** 同时实现 Tool + InteractiveTool 的桩：验证走 executeInteractive 而非 execute。 */
    private static class InteractiveStub implements Tool, InteractiveTool {
        final String name;
        final List<String> log;

        InteractiveStub(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String description() {
            return "interactive stub";
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Permission permission() {
            return Permission.READ;
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode input, ToolContext context) {
            log.add(name + "_execute");
            return ToolResult.success("from-execute");
        }

        @Override
        public ToolResult executeInteractive(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
            log.add(name + "_interactive");
            return ToolResult.success("B").withDisplay("menu-choice");
        }
    }

    /** executeInteractive 阻塞直至 release 的桩：测取消路径。 */
    private static class BlockingInteractiveStub implements Tool, InteractiveTool {
        final String name;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        BlockingInteractiveStub(String name) {
            this.name = name;
        }

        @Override
        public String description() {
            return "blocking interactive stub";
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Permission permission() {
            return Permission.READ;
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public ToolResult execute(JsonNode input, ToolContext context) {
            return ToolResult.success("from-execute");
        }

        @Override
        public ToolResult executeInteractive(ToolUseBlock call, BlockingQueue<AgentEvent> events, AtomicBoolean cancelled) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("late");
        }
    }

    @Test
    void interactiveToolRunsExecuteInteractiveInsteadOfExecute() {
        List<String> log = new ArrayList<>();
        Tool tool = new InteractiveStub("AskUser", log);
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(tool));
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "AskUser")), events, new AtomicBoolean(false));

        assertEquals(1, results.size());
        assertEquals("B", results.get(0).output());
        assertFalse(results.get(0).isError());
        assertTrue(log.contains("AskUser_interactive"), "交互工具应走 executeInteractive");
        assertFalse(log.contains("AskUser_execute"), "不应走 BaseTool.execute 路径");
    }

    @Test
    void interactiveToolResultEventHasZeroElapsed() {
        Tool tool = new InteractiveStub("AskUser", new ArrayList<>());
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(tool));
        executor.execute(List.of(call("id1", "AskUser")), events, new AtomicBoolean(false));

        List<AgentEvent> list = new ArrayList<>();
        events.drainTo(list);
        assertEquals(1, list.size());
        ToolResultEvent e = (ToolResultEvent) list.get(0);
        assertEquals("id1", e.toolId());
        assertEquals("B", e.output());
        assertFalse(e.isError());
        assertEquals(0, e.elapsedMs(), "交互耗时记 0（不含用户思考时间）");
        assertEquals("menu-choice", e.display(), "交互路径事件 display 应与 result.display() 一致");
    }

    @Test
    void interactiveReadToolSkipsConfirmationGate() {
        AtomicBoolean gateCalled = new AtomicBoolean(false);
        ConfirmationGate trackingGate = (call, events, cancelled) -> {
            gateCalled.set(true);
            return PermissionResponse.DENY;
        };
        List<String> log = new ArrayList<>();
        Tool tool = new InteractiveStub("AskUser", log);
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = executor(registry(tool), trackingGate);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "AskUser")), events, new AtomicBoolean(false));

        assertFalse(results.get(0).isError(), "READ 交互工具应正常执行");
        assertFalse(gateCalled.get(), "READ 交互工具不应触发确认门槛");
        assertTrue(log.contains("AskUser_interactive"));
    }

    @Test
    void interactiveToolCancelledFillsPlaceholderWithoutEvent() throws Exception {
        BlockingInteractiveStub tool = new BlockingInteractiveStub("AskUser");
        BlockingQueue<AgentEvent> events = queue();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StreamingToolExecutor executor = executor(registry(tool));
        java.util.concurrent.Future<List<ToolResult>> future = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> executor.execute(List.of(call("id1", "AskUser")), events, cancelled));

        assertTrue(tool.entered.await(2, TimeUnit.SECONDS), "交互执行应已进入");
        cancelled.set(true);
        tool.release.countDown();
        List<ToolResult> results = future.get(5, TimeUnit.SECONDS);

        assertTrue(results.get(0).isError(), "取消的交互调用应补「已取消」");
        assertEquals("已取消", results.get(0).errorMessage());
        List<AgentEvent> list = new ArrayList<>();
        events.drainTo(list);
        assertTrue(list.isEmpty(), "取消的交互调用不应发 ToolResultEvent");
    }

    // ---- 阶段五：权限检查接入（T9） ----

    @Test
    void blacklistedCommandDeniedEvenInBypassModeWithUniquePrefix() {
        Tool bash = new RecordingTool("Bash", Permission.EXEC, new ArrayList<>());
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = new StreamingToolExecutor(registry(bash),
                new ToolContext(tempDir), checker(PermissionMode.BYPASS), ConfirmationGate.ALWAYS_ALLOW);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "Bash", JSON.createObjectNode().put("command", "rm -rf /"))),
                events, new AtomicBoolean(false));

        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().startsWith("权限拒绝：危险命令："),
                "前缀应唯一：" + results.get(0).content());
        assertTrue(!results.get(0).content().contains("权限拒绝：权限拒绝"),
                "不得出现重复前缀");
    }

    @Test
    void askDeniedReturnsUserRejectionFailure() {
        Tool write = new RecordingTool("WriteFile", Permission.WRITE, new ArrayList<>());
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = new StreamingToolExecutor(registry(write),
                new ToolContext(tempDir), checker(PermissionMode.DEFAULT), DENY);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "WriteFile",
                        JSON.createObjectNode().put("file_path", tempDir.resolve("a.txt").toString()))),
                events, new AtomicBoolean(false));

        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().contains("用户拒绝执行"));
    }

    @Test
    void askAllowExecutesTool() {
        List<String> log = new ArrayList<>();
        Tool write = new RecordingTool("WriteFile", Permission.WRITE, log);
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = new StreamingToolExecutor(registry(write),
                new ToolContext(tempDir), checker(PermissionMode.DEFAULT), APPROVE);
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "WriteFile",
                        JSON.createObjectNode().put("file_path", tempDir.resolve("a.txt").toString()))),
                events, new AtomicBoolean(false));

        assertFalse(results.get(0).isError());
        assertTrue(log.contains("WriteFile_start"), "批准后工具应执行");
    }

    @Test
    void allowAlwaysPersistsAndSkipsGateOnSecondCall() throws Exception {
        List<String> log = new ArrayList<>();
        Tool write = new RecordingTool("WriteFile", Permission.WRITE, log);
        AtomicBoolean gateCalled = new AtomicBoolean(false);
        ConfirmationGate alwaysGate = (call, events, cancelled) -> {
            gateCalled.set(true);
            return PermissionResponse.ALLOW_ALWAYS;
        };
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = new StreamingToolExecutor(registry(write),
                new ToolContext(tempDir), checker(PermissionMode.DEFAULT), alwaysGate);
        JsonNode args = JSON.createObjectNode().put("file_path", tempDir.resolve("a.txt").toString());

        executor.execute(List.of(call("id1", "WriteFile", args)), events, new AtomicBoolean(false));
        assertTrue(log.contains("WriteFile_start"), "始终允许后应执行");
        assertTrue(gateCalled.get());

        // 第二次同参数：会话「始终允许」命中，不再弹确认
        gateCalled.set(false);
        executor.execute(List.of(call("id2", "WriteFile", args)), events, new AtomicBoolean(false));
        assertFalse(gateCalled.get(), "第二次应走会话「始终允许」直接放行");
        assertTrue(Files.exists(tempDir.resolve("l.yaml")), "「始终允许」应持久化到本地规则文件");
        assertTrue(Files.readString(tempDir.resolve("l.yaml")).contains("effect: allow"));
    }

    @Test
    void readToolOutsideSandboxDenied() {
        Tool read = new RecordingTool("ReadFile", Permission.READ, new ArrayList<>());
        BlockingQueue<AgentEvent> events = queue();
        StreamingToolExecutor executor = new StreamingToolExecutor(registry(read),
                new ToolContext(tempDir), checker(PermissionMode.DEFAULT), ConfirmationGate.ALWAYS_ALLOW);
        String outside = Path.of(System.getProperty("user.home")).resolve("secret.txt").toString();
        List<ToolResult> results = executor.execute(
                List.of(call("id1", "ReadFile", JSON.createObjectNode().put("file_path", outside))),
                events, new AtomicBoolean(false));

        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).content().startsWith("权限拒绝：路径"));
        assertTrue(results.get(0).content().contains("超出沙箱范围"));
    }
}
