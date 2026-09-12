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
 * <p>告警不直接打 stderr，而由 {@link #drainWarnings()} 交给调用方在 UI 输出区渲染：
 * 直接写 stderr 既会因控制台按代码页解码而乱码，也会跑到界面上方打乱排版。
 */
public class McpManager implements AutoCloseable {

    private final Map<String, McpServerConnection> connections = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public McpManager(AppConfig config, Path workingDirectory) {
        for (Map.Entry<String, McpServerConfig> entry : config.getMcpServers().entrySet()) {
            McpServerConfig serverConfig = entry.getValue();
            if (serverConfig.enabled()) {
                connections.put(entry.getKey(), new McpServerConnection(entry.getKey(), serverConfig,
                        workingDirectory, null, this::warn));
            }
        }
    }

    /** 已启用的 server 名（声明顺序）；空表示本次启动不涉及 MCP。 */
    public List<String> serverNames() {
        return List.copyOf(connections.keySet());
    }

    /** 取走并清空累积的告警（连接失败、工具重名、协议版本不一致），由调用方展示；无告警返回空表。 */
    public List<String> drainWarnings() {
        synchronized (warnings) {
            List<String> drained = List.copyOf(warnings);
            warnings.clear();
            return drained;
        }
    }

    private void warn(String message) {
        synchronized (warnings) {
            warnings.add(message);
        }
    }

    /** 逐个连接全部 server；任一失败记告警并继续，其余 server 不受影响。 */
    public void connectAll() {
        for (Map.Entry<String, McpServerConnection> entry : connections.entrySet()) {
            try {
                entry.getValue().connect();
            } catch (RuntimeException e) {
                warn("警告：MCP server " + entry.getKey() + " 连接失败：" + e.getMessage());
            }
        }
    }

    /** 逐个注册工具适配器；同名冲突记告警跳过该工具。 */
    public void registerTools(ToolRegistry registry) {
        for (McpServerConnection connection : connections.values()) {
            for (McpToolWrapper wrapper : connection.tools()) {
                try {
                    registry.register(wrapper);
                } catch (IllegalArgumentException e) {
                    warn("警告：跳过 MCP 工具 " + wrapper.name() + "：" + e.getMessage());
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
