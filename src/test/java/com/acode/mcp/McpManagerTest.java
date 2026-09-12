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

import java.io.IOException;
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
 * closeAll 幂等，失败/重名告警经 drainWarnings() 可观测（不再写 stderr）。
 */
class McpManagerTest {

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

    private static String drainWarnings(McpManager manager) {
        return String.join("\n", manager.drainWarnings());
    }

    @Test
    void oneServerFailureDoesNotBlockOthers() throws IOException {
        AppConfig config = configWith(Map.of(
                "a", stdioServerYaml(),
                "b", Map.of("type", "http", "url", deadUrl(), "timeout", 5)));
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(config, WORK_DIR);
        manager.connectAll();
        manager.registerTools(registry);
        String warnings = drainWarnings(manager);
        assertNotNull(registry.get("a_echo"), "server A 的工具应注册成功");
        assertNotNull(registry.get("a_echo_env"));
        assertTrue(warnings.contains("server b"), "应记录 b 的失败告警");
        assertTrue(warnings.contains("连接失败"), "告警应含失败原因");
        manager.closeAll();
    }

    @Test
    void duplicateToolNameSkippedWithWarning() {
        AppConfig config = configWith(Map.of("a", stdioServerYaml()));
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("a_echo")); // 预注册同名
        McpManager manager = new McpManager(config, WORK_DIR);
        manager.connectAll();
        manager.registerTools(registry);
        String warnings = drainWarnings(manager);
        assertTrue(warnings.contains("a_echo"), "重名工具应记录跳过告警");
        assertNotNull(registry.get("a_echo_env"), "其余工具仍应注册");
        manager.closeAll();
    }

    @Test
    void disabledServerIsNotConnectedNorWarned() throws IOException {
        AppConfig config = configWith(Map.of(
                "a", stdioServerYaml(),
                "b", Map.of("type", "http", "url", deadUrl(), "enabled", false, "timeout", 5)));
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(config, WORK_DIR);
        manager.connectAll();
        manager.registerTools(registry);
        String warnings = drainWarnings(manager);
        assertFalse(warnings.contains("server b"), "enabled=false 不应连接也不应告警");
        assertNotNull(registry.get("a_echo"), "enabled 的 server 正常注册");
        manager.closeAll();
    }

    @Test
    void closeAllIsIdempotentAndClosesAll() {
        AppConfig config = configWith(Map.of("a", stdioServerYaml()));
        McpManager manager = new McpManager(config, WORK_DIR);
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
