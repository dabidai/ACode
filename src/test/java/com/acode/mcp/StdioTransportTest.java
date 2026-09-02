package com.acode.mcp;

import com.acode.mcp.fakeserver.FakeMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StdioTransport 集成测试：真实拉起 FakeMcpServer 子进程验证全双工帧、进程死亡、
 * 关闭清理与环境隔离（白名单被真正应用到子进程）。
 */
class StdioTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /** 用当前测试 JVM 的完整 classpath 启动 FakeMcpServer 子进程（surefire 下含依赖 jar） */
    private static List<String> fakeServerCommand() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                ProcessEnv.isWindows() ? "java.exe" : "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                "com.acode.mcp.fakeserver.FakeMcpServer");
    }

    @Test
    void sendRequestReceivesResponseCallback() throws Exception {
        StdioTransport transport = new StdioTransport(fakeServerCommand(), tempDir);
        try {
            CountDownLatch responseLatch = new CountDownLatch(1);
            AtomicReference<JsonRpcMessage> received = new AtomicReference<>();
            transport.setMessageHandler(m -> {
                received.set(m);
                responseLatch.countDown();
            });
            transport.start();
            assertTrue(transport.isAlive(), "启动后传输应存活");
            transport.send(new JsonRpcMessage.Request("initialize", JSON.createObjectNode(), "1"));
            assertTrue(responseLatch.await(10, TimeUnit.SECONDS), "应收到 initialize 响应");
            assertInstanceOf(JsonRpcMessage.Response.class, received.get());
            JsonRpcMessage.Response response = (JsonRpcMessage.Response) received.get();
            assertEquals("2025-06-18", response.result().path("protocolVersion").asText());
        } finally {
            transport.close();
        }
    }

    @Test
    void subprocessDeathMarksDeadAndTriggersTermination() throws Exception {
        StdioTransport transport = new StdioTransport(fakeServerCommand(), tempDir);
        CountDownLatch terminated = new CountDownLatch(1);
        transport.setTerminationHandler(terminated::countDown);
        transport.start();
        transport.send(new JsonRpcMessage.Request("tools/call",
                callParams("kill", JSON.createObjectNode()), "1"));
        assertTrue(terminated.await(10, TimeUnit.SECONDS), "子进程退出应触发终止回调");
        assertFalse(transport.isAlive(), "进程死亡后 isAlive 应为 false");
        assertThrows(McpException.class,
                () -> transport.send(new JsonRpcMessage.Request("tools/list", null, "2")),
                "死亡传输 send 应抛连接失败");
        transport.close();
    }

    @Test
    void closeDestroysSubprocessAndMarksDead() throws Exception {
        StdioTransport transport = new StdioTransport(fakeServerCommand(), tempDir);
        CountDownLatch terminated = new CountDownLatch(1);
        transport.setTerminationHandler(terminated::countDown);
        transport.start();
        assertTrue(transport.isAlive());
        transport.close();
        assertFalse(transport.isAlive(), "close 后 isAlive 应为 false");
        assertTrue(terminated.await(5, TimeUnit.SECONDS), "close 应触发终止回调");
        transport.close(); // 幂等
    }

    @Test
    void buildCommandWrapsCmdBatOnWindows() {
        assertEquals(List.of("cmd", "/c", "npx", "-y", "@x/server"),
                StdioTransport.buildCommand("Windows 11", List.of("npx", "-y", "@x/server")),
                "裸 npx 应经 cmd /c 包装");
        assertEquals(List.of("cmd", "/c", "C:\\x\\npx.cmd", "-y", "y"),
                StdioTransport.buildCommand("Windows 11", List.of("C:\\x\\npx.cmd", "-y", "y")),
                ".cmd 应经 cmd /c 包装");
        assertEquals(List.of("C:\\jdk\\bin\\java.exe", "-cp", "x"),
                StdioTransport.buildCommand("Windows 11", List.of("C:\\jdk\\bin\\java.exe", "-cp", "x")),
                ".exe 应直启不包装");
        assertEquals(List.of("npx", "-y", "y"),
                StdioTransport.buildCommand("Linux", List.of("npx", "-y", "y")),
                "非 Windows 一律不包装");
    }

    @Test
    void subprocessReceivesOnlyWhitelistedEnvironment() throws Exception {
        List<String> whitelist = ProcessEnv.keysFor(System.getProperty("os.name"));
        String excludedKey = System.getenv().keySet().stream()
                .filter(k -> !whitelist.contains(k) && !k.isBlank())
                .findFirst()
                .orElse("ACODE_TEST_UNSET");
        StdioTransport transport = new StdioTransport(fakeServerCommand(), tempDir);
        try {
            CountDownLatch latch = new CountDownLatch(2);
            ConcurrentHashMap<String, String> responses = new ConcurrentHashMap<>();
            transport.setMessageHandler(m -> {
                if (m instanceof JsonRpcMessage.Response response) {
                    responses.put(response.id(), textFrom(response.result()));
                    latch.countDown();
                }
            });
            transport.start();
            transport.send(new JsonRpcMessage.Request("tools/call",
                    callParams("echo_env", JSON.createObjectNode().put("name", "PATH")), "1"));
            transport.send(new JsonRpcMessage.Request("tools/call",
                    callParams("echo_env", JSON.createObjectNode().put("name", excludedKey)), "2"));
            assertTrue(latch.await(10, TimeUnit.SECONDS), "两条 echo_env 都应收到响应");
            assertEquals(System.getenv("PATH"), responses.get("1"), "白名单 PATH 应透传给子进程");
            assertEquals("(null)", responses.get("2"), "未声明变量不应透传（环境隔离生效）");
        } finally {
            transport.close();
        }
    }

    private static JsonNode callParams(String name, JsonNode arguments) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", name);
        node.set("arguments", arguments);
        return node;
    }

    private static String textFrom(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }
}
