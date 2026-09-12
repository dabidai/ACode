package com.acode;

import com.acode.config.AppConfig;
import com.acode.config.McpServerConfig;
import com.acode.mcp.ProcessEnv;
import com.acode.provider.FakeProvider;
import com.acode.ui.OutputPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 装配集成：构造 ConversationController（配置了 mcp_servers）→ 显式驱动 connectMcp()
 * （真实流程由 start() 在 banner 输出后调用）→ 首轮 Agent 请求的工具列表已含 MCP 工具名
 * （注册时机在 Agent 首次构建前）。
 */
class McpWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void configuredMcpServerToolsRegisteredBeforeAgentBuilds() throws Exception {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("test-model");
        config.setMaxContextTokens(8000);
        config.setMemoryAuto(false);
        List<String> command = fakeServerCommand();
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("type", "stdio");
        yaml.put("command", command.get(0));
        yaml.put("args", command.subList(1, command.size()));
        yaml.put("timeout", 5);
        config.setMcpServers(Map.of("srv", McpServerConfig.fromYaml("srv", yaml, "test")));

        FakeProvider provider = FakeProvider.scripted(List.of(List.of(FakeProvider.complete())));
        ConversationController controller = new ConversationController(provider, config, false);
        try {
            controller.setProjectRoot(tempDir); // 会话/记忆落盘必须落在临时目录，不得写进真实仓库
            OutputPane output = new OutputPane();
            controller.setOutput(output);
            controller.setScreenWriter(new StringWriter());
            controller.connectMcp(); // 真实流程里由 start() 在 banner 之后就调用
            assertTrue(output.lines().stream().anyMatch(line -> line.contains("正在连接 MCP server：srv")),
                    "连接提示应进输出区而非裸 stderr：" + output.lines());
            controller.handleExchange("hello", () -> false, () -> { });
            var requests = provider.receivedRequests();
            assertFalse(requests.isEmpty(), "应发出首轮请求");
            boolean hasMcpTool = requests.get(0).tools().stream()
                    .anyMatch(tool -> "srv_echo".equals(tool.name()));
            assertTrue(hasMcpTool, "Agent 首轮请求工具列表应含 MCP 工具 srv_echo（注册在 Agent 构建前）");
        } finally {
            controller.closeMcpManager(); // 清理 stdio 子进程，避免测试残留
        }
    }

    private static List<String> fakeServerCommand() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                ProcessEnv.isWindows() ? "java.exe" : "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                "com.acode.mcp.fakeserver.FakeMcpServer");
    }
}
