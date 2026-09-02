package com.acode.mcp;

import com.acode.config.AppConfig;
import com.acode.config.McpServerConfig;
import com.acode.mcp.fakeserver.FakeMcpServer;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpManager 测试：单个 server 失败不阻断其余、注册重名跳过、enabled=false 跳过、
 * closeAll 幂等，失败/重名警告可观测（捕获 System.err）。
 */
class McpManagerTest {

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

    private static String deadUrl() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = s.getLocalPort();
        }
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + deadPort + "/";
    }

    private AppConfig configWith(Map<String, Map<String, Object>> servers) {
        AppConfig config = new AppConfig();
        Map<String, McpServerConfig> parsed = new LinkedHashMap<>();
        servers.forEach((name, yaml) -> parsed.put(name, McpServerConfig.fromYaml(name, yaml, "test")));
        config.setMcpServers(parsed);
        return config;
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

    private static String captureErr(Runnable runnable) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true));
        try {
            runnable.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString();
    }

    @Test
    void oneServerFailureDoesNotBlockOthers() throws IOException {
        AppConfig config = configWith(Map.of(
                "a", stdioServerYaml(),
                "b", Map.of("type", "http", "url", deadUrl(), "timeout", 5)));
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(config, tempDir);
        String captured = captureErr(() -> {
            manager.connectAll();
            manager.registerTools(registry);
        });
        assertNotNull(registry.get("a_echo"), "server A 的工具应注册成功");
        assertNotNull(registry.get("a_echo_env"));
        assertTrue(captured.contains("server b"), "应输出 b 的失败警告");
        assertTrue(captured.contains("连接失败"), "警告应含失败原因");
        manager.closeAll();
    }

    @Test
    void duplicateToolNameSkippedWithWarning() {
        AppConfig config = configWith(Map.of("a", stdioServerYaml()));
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("a_echo")); // 预注册同名
        McpManager manager = new McpManager(config, tempDir);
        String captured = captureErr(() -> {
            manager.connectAll();
            manager.registerTools(registry);
        });
        assertTrue(captured.contains("a_echo"), "重名工具应输出跳过警告");
        assertNotNull(registry.get("a_echo_env"), "其余工具仍应注册");
        manager.closeAll();
    }

    @Test
    void disabledServerIsNotConnectedNorWarned() throws IOException {
        AppConfig config = configWith(Map.of(
                "a", stdioServerYaml(),
                "b", Map.of("type", "http", "url", deadUrl(), "enabled", false, "timeout", 5)));
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(config, tempDir);
        String captured = captureErr(() -> {
            manager.connectAll();
            manager.registerTools(registry);
        });
        assertFalse(captured.contains("server b"), "enabled=false 不应连接也不应警告");
        assertNotNull(registry.get("a_echo"), "enabled 的 server 正常注册");
        manager.closeAll();
    }

    @Test
    void closeAllIsIdempotentAndClosesAll() {
        AppConfig config = configWith(Map.of("a", stdioServerYaml()));
        McpManager manager = new McpManager(config, tempDir);
        manager.connectAll();
        manager.closeAll();
        manager.closeAll(); // 幂等
        manager.close();    // AutoCloseable 入口
    }

    private static Tool dummyTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "dummy";
            }

            @Override
            public Permission permission() {
                return Permission.EXEC;
            }

            @Override
            public JsonNode inputSchema() {
                return JSON.createObjectNode().put("type", "object");
            }

            @Override
            public ToolResult execute(JsonNode input, ToolContext context) {
                return ToolResult.success("dummy");
            }
        };
    }
}
