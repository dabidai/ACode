package com.acode.mcp;

import com.acode.config.McpServerConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单 server 活连接缓存单元：connect 幂等（关旧建新 → 握手 → 工具发现缓存适配器），
 * 工具调用遇连接死亡自动重连一次。
 * <p>并发契约（见 design-notes T6）：①重连单飞用「等锁」——callTool 遇死连接在重连锁上阻塞，
 * 同一时刻仅一个线程重建，其余等锁后复用新连接，等待受 McpClient 超时兜底；
 * ②wrapper 不绑定 client 实例，经 volatile {@code currentClient} 路由——重连建新后旧 wrapper 仍指向新连接；
 * ③close() 置 volatile closed 标志并与重连互斥（防 close 后重建僵尸连接）；
 * ④isAlive/closed 用 volatile 保证跨线程可见性。
 */
public class McpServerConnection implements AutoCloseable {

    private final String name;
    private final McpServerConfig config;
    private final Path workingDirectory;
    private final Function<McpServerConfig, Transport> transportFactory;
    private final Consumer<String> warningSink;
    private final ReentrantLock connectLock = new ReentrantLock();
    private final AtomicInteger connectCount = new AtomicInteger();

    private volatile McpClient currentClient;
    private volatile boolean closed;
    private volatile List<McpToolWrapper> discoveredTools = List.of();

    public McpServerConnection(String name, McpServerConfig config, Path workingDirectory) {
        this(name, config, workingDirectory, null, null);
    }

    /** 包可见：测试注入 transport 工厂（每次建连调用一次）。 */
    McpServerConnection(String name, McpServerConfig config, Path workingDirectory,
                        Function<McpServerConfig, Transport> transportFactory) {
        this(name, config, workingDirectory, transportFactory, null);
    }

    /** warningSink 收拢握手期告警，透传给每次新建的 client；null 表示丢弃。 */
    McpServerConnection(String name, McpServerConfig config, Path workingDirectory,
                        Function<McpServerConfig, Transport> transportFactory, Consumer<String> warningSink) {
        this.name = name;
        this.config = config;
        this.workingDirectory = workingDirectory;
        this.transportFactory = transportFactory != null ? transportFactory : this::buildTransport;
        this.warningSink = warningSink;
    }

    /** 连接（关旧建新）→ 握手 → 工具发现并缓存适配器。幂等；与 close/重连互斥。 */
    public void connect() {
        connectLock.lock();
        try {
            if (closed) {
                throw McpException.connectionFailed("server 已关闭：" + name);
            }
            connectLocked();
        } finally {
            connectLock.unlock();
        }
    }

    /** 必须在持有 connectLock 时调用。 */
    private void connectLocked() {
        McpClient old = currentClient;
        if (old != null) {
            old.close();
        }
        McpClient client = buildClient();
        try {
            client.initialize();
            List<McpToolInfo> infos = client.listTools();
            List<McpToolWrapper> wrappers = infos.stream()
                    .map(info -> new McpToolWrapper(name, info, config.permission(), this))
                    .toList();
            currentClient = client;
            discoveredTools = List.copyOf(wrappers);
            connectCount.incrementAndGet();
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    private McpClient buildClient() {
        Transport transport = transportFactory.apply(config);
        transport.start();
        return new McpClient(transport, Duration.ofSeconds(config.timeoutSeconds()), warningSink);
    }

    private Transport buildTransport(McpServerConfig config) {
        if (config.type() == McpServerConfig.Type.STDIO) {
            return new StdioTransport(config.command(), workingDirectory, config.env());
        }
        return new HttpTransport(config.url(), config.headers(), Duration.ofSeconds(config.timeoutSeconds()));
    }

    /** 已发现的工具适配器列表（重连后仍指向本连接，经 currentClient 路由到新 client）。 */
    public List<McpToolWrapper> tools() {
        return discoveredTools;
    }

    /** 调远端工具：连接死亡先重连一次；调用中出现连接级失败再重连一次并重试，不无限重试。 */
    public JsonNode callTool(String toolName, JsonNode arguments) {
        McpClient client = clientForCall();
        try {
            return client.callTool(toolName, arguments);
        } catch (McpException e) {
            if (e.kind() == McpException.Kind.CONNECTION) {
                McpClient fresh = reconnectIfNeeded();
                return fresh.callTool(toolName, arguments);
            }
            throw e;
        }
    }

    private McpClient clientForCall() {
        McpClient client = currentClient;
        if (client == null || !client.isAlive()) {
            return reconnectIfNeeded();
        }
        return client;
    }

    /** 等锁单飞重连：同一时刻仅一个线程重建连接，其余等锁后复用新连接。 */
    private McpClient reconnectIfNeeded() {
        connectLock.lock();
        try {
            if (closed) {
                throw McpException.connectionFailed("server 已关闭：" + name);
            }
            McpClient client = currentClient;
            if (client != null && client.isAlive()) {
                return client; // 等锁期间已有线程重连成功，直接复用
            }
            connectLocked();
            return currentClient;
        } finally {
            connectLock.unlock();
        }
    }

    public boolean isAlive() {
        McpClient client = currentClient;
        return !closed && client != null && client.isAlive();
    }

    /** close 与重连互斥：置 closed 后并发 callTool/重连不重建连接；in-flight 请求被客户端异常完成。 */
    @Override
    public void close() {
        closed = true;
        connectLock.lock();
        try {
            McpClient client = currentClient;
            currentClient = null;
            if (client != null) {
                client.close();
            }
        } finally {
            connectLock.unlock();
        }
    }

    /** 测试可见：成功建连次数。 */
    int connectCount() {
        return connectCount.get();
    }
}
