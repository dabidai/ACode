package com.acode.mcp;

import com.acode.config.McpServerConfig;
import com.acode.mcp.fakeserver.FakeMcpServer;
import com.acode.tool.Permission;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpToolWrapper + McpServerConnection 测试：真实拉起 FakeMcpServer 子进程验证
 * 适配（name 前缀/schema 透传/权限映射/异常转失败）与连接单元的并发契约
 * （等锁单飞重连、重连后旧 wrapper 可用、close 与重连互斥、in-flight 异常完成）。
 */
class McpToolWrapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 子进程 CWD：不可用 @TempDir，原因见 {@link SubprocessWorkingDir} */
    private static final Path WORK_DIR = SubprocessWorkingDir.get();

    /** 用当前测试 JVM 的完整 classpath 启动 FakeMcpServer 子进程（surefire 下含依赖 jar） */
    private static List<String> fakeServerCommand() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                ProcessEnv.isWindows() ? "java.exe" : "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                "com.acode.mcp.fakeserver.FakeMcpServer");
    }

    private McpServerConfig stdioConfig(String name, long timeoutSeconds) {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("type", "stdio");
        yaml.put("command", fakeServerCommand().get(0));
        yaml.put("args", fakeServerCommand().subList(1, fakeServerCommand().size()));
        yaml.put("timeout", timeoutSeconds);
        return McpServerConfig.fromYaml(name, yaml, "test");
    }

    private McpServerConnection connection(String name) {
        return new McpServerConnection(name, stdioConfig(name, 5), WORK_DIR);
    }

    private static McpToolWrapper wrapperFor(McpServerConnection connection, String toolName) {
        String fullName = "srv_" + toolName;
        return connection.tools().stream()
                .filter(wrapper -> wrapper.name().equals(fullName))
                .findFirst().orElseThrow(() -> new AssertionError("未找到工具 " + fullName));
    }

    private static void awaitDead(McpServerConnection connection) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (connection.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(connection.isAlive(), "子进程应已死亡");
    }

    @Test
    void nameHasPrefixAndSchemaPassthrough() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        McpToolInfo info = new McpToolInfo("echo", "回显", schema);
        McpToolWrapper wrapper = new McpToolWrapper("srv", info, Permission.EXEC, null);
        assertEquals("srv_echo", wrapper.name(), "注册名应为 server名_工具名");
        assertTrue(wrapper.description().contains("MCP server srv"), "描述应标注来源 server");
        assertSame(schema, wrapper.inputSchema(), "inputSchema 应原样透传");
    }

    @Test
    void permissionStoredFromServerConfig() {
        McpToolInfo info = new McpToolInfo("t", "d", JSON.createObjectNode());
        assertEquals(Permission.EXEC, new McpToolWrapper("srv", info, Permission.EXEC, null).permission());
        assertEquals(Permission.READ, new McpToolWrapper("srv", info, Permission.READ, null).permission());
    }

    @Test
    void executeReturnsSuccessOnText() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        try {
            McpToolWrapper wrapper = wrapperFor(connection, "echo");
            ToolResult result = wrapper.execute(JSON.createObjectNode().put("text", "hi"), null);
            assertTrue(result.isSuccess());
            assertEquals("hi", result.output(), "成功结果为远端 text 段");
        } finally {
            connection.close();
        }
    }

    @Test
    void executeIsErrorReturnsFailure() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        try {
            McpToolInfo badInfo = new McpToolInfo("nonexistent", "d", JSON.createObjectNode());
            McpToolWrapper bad = new McpToolWrapper("srv", badInfo, Permission.EXEC, connection);
            ToolResult result = bad.execute(JSON.createObjectNode(), null);
            assertTrue(result.isError());
            assertTrue(result.errorMessage().contains("未知工具"), "失败结果应含远端错误文本");
        } finally {
            connection.close();
        }
    }

    @Test
    void executeOnClosedConnectionReturnsFailureNotThrow() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        McpToolInfo info = new McpToolInfo("echo", "d", JSON.createObjectNode());
        McpToolWrapper wrapper = new McpToolWrapper("srv", info, Permission.EXEC, connection);
        connection.close();
        ToolResult result = wrapper.execute(JSON.createObjectNode().put("text", "hi"), null);
        assertTrue(result.isError(), "连接异常应转失败结果而非抛异常");
    }

    @Test
    void deadConnectionReconnectsOnceThenSucceeds() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        assertEquals(1, connection.connectCount());
        connection.callTool("kill", JSON.createObjectNode());
        awaitDead(connection);
        McpToolWrapper wrapper = wrapperFor(connection, "echo");
        ToolResult result = wrapper.execute(JSON.createObjectNode().put("text", "again"), null);
        assertTrue(result.isSuccess(), "重连后应成功，失败：" + result.errorMessage());
        assertEquals("again", result.output());
        assertEquals(2, connection.connectCount(), "应恰好重连一次");
        connection.close();
    }

    @Test
    void disconnectAfterCallDoesNotRetryUnknownOutcome() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        try {
            McpToolWrapper drop = wrapperFor(connection, "drop");
            ToolResult result = drop.execute(JSON.createObjectNode(), null);
            assertTrue(result.isError());
            assertTrue(result.errorMessage().contains("可能已执行"));
            assertEquals(1, connection.connectCount(), "请求送达后断线，不应重连并重发调用");
        } finally {
            connection.close();
        }
    }

    @Test
    void reconnectFailureReturnsFailureNotInfiniteRetry() throws Exception {
        AtomicInteger created = new AtomicInteger();
        Function<McpServerConfig, Transport> factory = cfg -> {
            if (created.incrementAndGet() > 1) {
                return new FailingTransport();
            }
            return new StdioTransport(fakeServerCommand(), WORK_DIR);
        };
        McpServerConnection connection = new McpServerConnection("srv", stdioConfig("srv", 5), WORK_DIR, factory);
        connection.connect();
        connection.callTool("kill", JSON.createObjectNode());
        awaitDead(connection);
        McpToolWrapper wrapper = wrapperFor(connection, "echo");
        long start = System.currentTimeMillis();
        ToolResult result = wrapper.execute(JSON.createObjectNode().put("text", "x"), null);
        assertTrue(result.isError(), "重连失败应返回失败结果");
        assertTrue(System.currentTimeMillis() - start < 3000, "不应无限重试");
        connection.close();
    }

    @Test
    void concurrentReconnectIsSingleFlight() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        connection.callTool("kill", JSON.createObjectNode());
        awaitDead(connection);
        McpToolWrapper echo = wrapperFor(connection, "echo");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ToolResult> r1 = CompletableFuture.supplyAsync(() -> {
                awaitStart(start);
                return echo.execute(JSON.createObjectNode().put("text", "a"), null);
            }, pool);
            CompletableFuture<ToolResult> r2 = CompletableFuture.supplyAsync(() -> {
                awaitStart(start);
                return echo.execute(JSON.createObjectNode().put("text", "b"), null);
            }, pool);
            start.countDown();
            ToolResult t1 = r1.get(10, TimeUnit.SECONDS);
            ToolResult t2 = r2.get(10, TimeUnit.SECONDS);
            assertTrue(t1.isSuccess(), "t1 应成功，失败：" + t1.errorMessage());
            assertTrue(t2.isSuccess(), "t2 应成功，失败：" + t2.errorMessage());
        }
        assertEquals(2, connection.connectCount(), "等锁单飞：两个并发 caller 只重建一次连接");
        connection.close();
    }

    @Test
    void oldWrapperWorksAfterReconnect() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        McpToolWrapper old = wrapperFor(connection, "echo");
        connection.callTool("kill", JSON.createObjectNode());
        awaitDead(connection);
        ToolResult result = old.execute(JSON.createObjectNode().put("text", "after"), null);
        assertTrue(result.isSuccess(), "重连前拿到的旧 wrapper 应路由到新 client，失败：" + result.errorMessage());
        assertEquals("after", result.output());
        assertEquals(2, connection.connectCount());
        connection.close();
    }

    @Test
    void closePreventsReconnectAndFailsCalls() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        connection.close();
        McpException e = assertThrows(McpException.class,
                () -> connection.callTool("echo", JSON.createObjectNode().put("text", "x")));
        assertEquals(McpException.Kind.CONNECTION, e.kind());
        assertEquals(1, connection.connectCount(), "close 后不应重建连接（无僵尸连接）");
    }

    @Test
    void closeCompletesInflightRequestsNotHang() throws Exception {
        McpServerConnection connection = connection("srv");
        connection.connect();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<JsonNode> inFlight = CompletableFuture.supplyAsync(
                    () -> connection.callTool("sleep", JSON.createObjectNode().put("ms", 5000)), pool);
            Thread.sleep(100); // 等请求挂起
            long start = System.currentTimeMillis();
            connection.close();
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> inFlight.get(3, TimeUnit.SECONDS));
            assertTrue(System.currentTimeMillis() - start < 3000, "close 应快速完成 in-flight 请求，不悬挂");
            assertTrue(ex.getCause() instanceof McpException, "in-flight 应异常完成");
            assertEquals(McpException.Kind.CONNECTION, ((McpException) ex.getCause()).kind());
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 启动即失败的传输：模拟重连失败。 */
    private static final class FailingTransport implements Transport {
        @Override
        public void start() {
            throw McpException.connectionFailed("模拟重连失败");
        }

        @Override
        public void send(JsonRpcMessage message) {
            throw McpException.connectionFailed("不可用");
        }

        @Override
        public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
