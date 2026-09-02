package com.acode.mcp;

import com.acode.config.AppConfig;
import com.acode.config.McpServerConfig;
import com.acode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 server 生命周期管理：启动逐个连接 enabled server（单个失败容错、警告不阻断），
 * 注册工具进工具中心（同名冲突跳过打警告），退出逆序清理子进程。
 */
public class McpManager implements AutoCloseable {

    private final Map<String, McpServerConnection> connections = new LinkedHashMap<>();

    public McpManager(AppConfig config, Path workingDirectory) {
        for (Map.Entry<String, McpServerConfig> entry : config.getMcpServers().entrySet()) {
            McpServerConfig serverConfig = entry.getValue();
            if (serverConfig.enabled()) {
                connections.put(entry.getKey(),
                        new McpServerConnection(entry.getKey(), serverConfig, workingDirectory));
            }
        }
    }

    /** 逐个连接全部 server；任一失败捕获异常打警告并继续，其余 server 不受影响。 */
    public void connectAll() {
        for (Map.Entry<String, McpServerConnection> entry : connections.entrySet()) {
            try {
                entry.getValue().connect();
            } catch (RuntimeException e) {
                System.err.println("警告：MCP server " + entry.getKey() + " 连接失败：" + e.getMessage());
            }
        }
    }

    /** 逐个注册工具适配器；同名冲突捕获异常打警告跳过该工具。 */
    public void registerTools(ToolRegistry registry) {
        for (McpServerConnection connection : connections.values()) {
            for (McpToolWrapper wrapper : connection.tools()) {
                try {
                    registry.register(wrapper);
                } catch (IllegalArgumentException e) {
                    System.err.println("警告：跳过 MCP 工具 " + wrapper.name() + "：" + e.getMessage());
                }
            }
        }
    }

    /** 逆序关闭全部连接；幂等，已死连接关闭失败不抛。 */
    public void closeAll() {
        List<McpServerConnection> reversed = new ArrayList<>(connections.values());
        Collections.reverse(reversed);
        for (McpServerConnection connection : reversed) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // 已死连接清理失败不阻断
            }
        }
    }

    @Override
    public void close() {
        closeAll();
    }
}
