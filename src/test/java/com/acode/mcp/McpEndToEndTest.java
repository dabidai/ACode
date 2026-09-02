package com.acode.mcp;

import com.acode.config.AppConfig;
import com.acode.config.McpServerConfig;
import com.acode.mcp.fakeserver.FakeMcpServer;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：配置 → McpManager → ToolRegistry → 工具适配器 execute → 拿到远端结果。
 * stdio（FakeMcpServer 子进程）与 HTTP（HttpServer 假 server）两条链路；懒重连（杀子进程后自动重建）。
 */
class McpEndToEndTest {

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

    private Map<String, Object> stdioServerYaml() {
        List<String> command = fakeServerCommand();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("type", "stdio");
        yaml.put("command", command.get(0));
        yaml.put("args", command.subList(1, command.size()));
        yaml.put("timeout", 5);
        return yaml;
    }

    private Map<String, Object> readServerYaml() {
        Map<String, Object> yaml = stdioServerYaml();
        yaml.put("permission", "read");
        return yaml;
    }

    private AppConfig configWith(String name, Map<String, Object> serverYaml) {
        AppConfig config = new AppConfig();
        config.setMcpServers(Map.of(name, McpServerConfig.fromYaml(name, serverYaml, "test")));
        return config;
    }

    private static String extractText(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }

    @Test
    void stdioEndToEnd() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(configWith("srv", stdioServerYaml()), tempDir);
        manager.connectAll();
        manager.registerTools(registry);
        Tool echo = registry.get("srv_echo");
        assertNotNull(echo, "stdio server 工具应注册");
        ToolResult result = echo.execute(JSON.createObjectNode().put("text", "hi-stdio"), null);
        assertTrue(result.isSuccess(), "stdio 链路应拿到远端结果：" + result.errorMessage());
        assertEquals("hi-stdio", result.output());
        manager.closeAll();
    }

    @Test
    void httpEndToEnd() throws IOException {
        HttpServer server = startHttpServer();
        try {
            String url = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort() + "/";
            ToolRegistry registry = new ToolRegistry();
            McpManager manager = new McpManager(
                    configWith("http_srv", Map.of("type", "http", "url", url, "timeout", 5)), tempDir);
            manager.connectAll();
            manager.registerTools(registry);
            Tool echo = registry.get("http_srv_echo");
            assertNotNull(echo, "HTTP server 工具应注册");
            ToolResult result = echo.execute(JSON.createObjectNode().put("text", "hi-http"), null);
            assertTrue(result.isSuccess(), "HTTP 链路应拿到远端结果：" + result.errorMessage());
            assertEquals("hi-http", result.output());
            manager.closeAll();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lazyReconnectAfterSubprocessKill() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(configWith("srv", stdioServerYaml()), tempDir);
        manager.connectAll();
        manager.registerTools(registry);
        Tool echo = registry.get("srv_echo");
        Tool kill = registry.get("srv_kill");
        assertNotNull(kill, "kill 工具应可发现");
        ToolResult killed = kill.execute(JSON.createObjectNode(), null);
        assertTrue(killed.isSuccess(), "kill 应先回包：" + killed.errorMessage());
        // 子进程已死 → 再次调用自动重连成功
        ToolResult again = echo.execute(JSON.createObjectNode().put("text", "reconnected"), null);
        assertTrue(again.isSuccess(), "杀进程后懒重连应成功：" + again.errorMessage());
        assertEquals("reconnected", again.output());
        manager.closeAll();
    }

    @Test
    void permissionMapsFromServerConfigEndToEnd() {
        ToolRegistry readRegistry = new ToolRegistry();
        McpManager readManager = new McpManager(configWith("read_srv", readServerYaml()), tempDir);
        readManager.connectAll();
        readManager.registerTools(readRegistry);
        Tool readTool = readRegistry.get("read_srv_echo");
        assertNotNull(readTool);
        assertEquals(Permission.READ, readTool.permission(), "声明 read 的 server 工具应为 READ（plan 模式可见）");

        ToolRegistry defaultRegistry = new ToolRegistry();
        McpManager defaultManager = new McpManager(configWith("srv", stdioServerYaml()), tempDir);
        defaultManager.connectAll();
        defaultManager.registerTools(defaultRegistry);
        Tool defaultTool = defaultRegistry.get("srv_echo");
        assertNotNull(defaultTool);
        assertEquals(Permission.EXEC, defaultTool.permission(), "未声明权限档的 server 工具默认 EXEC（plan 模式不可见）");

        readManager.closeAll();
        defaultManager.closeAll();
    }

    private static HttpServer startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            JsonRpcMessage message = JsonRpcCodec.parse(body);
            String reply = null;
            if (message instanceof JsonRpcMessage.Request request) {
                switch (request.method()) {
                    case "initialize" -> {
                        ObjectNode result = JSON.createObjectNode();
                        result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                        result.putObject("capabilities").putObject("tools");
                        reply = JsonRpcCodec.serializeResponse(request.id(), result);
                    }
                    case "tools/list" -> {
                        ObjectNode result = JSON.createObjectNode();
                        var tools = result.putArray("tools");
                        ObjectNode echo = tools.addObject();
                        echo.put("name", "echo");
                        echo.put("description", "回显");
                        echo.putObject("inputSchema").put("type", "object");
                        reply = JsonRpcCodec.serializeResponse(request.id(), result);
                    }
                    case "tools/call" -> {
                        String name = request.params().path("name").asText("");
                        if ("echo".equals(name)) {
                            String text = request.params().path("arguments").path("text").asText("");
                            ObjectNode result = JSON.createObjectNode();
                            result.put("content", JSON.createArrayNode().add(
                                    JSON.createObjectNode().put("type", "text").put("text", text)));
                            reply = JsonRpcCodec.serializeResponse(request.id(), result);
                        } else {
                            ObjectNode result = JSON.createObjectNode();
                            result.put("isError", true);
                            result.put("content", JSON.createArrayNode().add(
                                    JSON.createObjectNode().put("type", "text").put("text", "未知工具：" + name)));
                            reply = JsonRpcCodec.serializeResponse(request.id(), result);
                        }
                    }
                    default -> reply = JsonRpcCodec.serializeError(
                            request.id(), JsonRpcCodec.METHOD_NOT_FOUND, "Method not found", null);
                }
            }
            if (reply == null) {
                exchange.sendResponseHeaders(202, -1); // 通知：无 body
                exchange.close();
                return;
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
