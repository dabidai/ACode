package com.acode.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * stdio 传输：启动子进程，经 stdin/stdout 换行分隔 JSON 帧全双工通信。
 * 环境严格白名单化（{@link ProcessEnv}）；Windows 下非原生二进制命令经 {@code cmd /c} 包装。
 * 关闭顺序：destroy → 等 2 秒 → destroyForcibly；EOF / 进程退出触发终止回调。
 */
public class StdioTransport implements Transport {

    private static final long DESTROY_WAIT_MILLIS = 2000;

    private final List<String> command;
    private final Path workingDirectory;
    private final Map<String, String> envOverrides;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean dead = new AtomicBoolean();

    private volatile Consumer<JsonRpcMessage> messageHandler = message -> { };
    private volatile Runnable terminationHandler = () -> { };

    private Process process;
    private Writer stdin;

    public StdioTransport(List<String> command, Path workingDirectory) {
        this(command, workingDirectory, Map.of());
    }

    public StdioTransport(List<String> command, Path workingDirectory, Map<String, String> envOverrides) {
        this.command = List.copyOf(command);
        this.workingDirectory = workingDirectory;
        this.envOverrides = envOverrides == null ? Map.of() : Map.copyOf(envOverrides);
    }

    @Override
    public synchronized void start() {
        if (closed.get()) {
            throw McpException.connectionFailed("传输已关闭");
        }
        if (process != null && process.isAlive()) {
            return; // 幂等
        }
        List<String> fullCommand = buildCommand(command);
        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(ProcessEnv.build(envOverrides));
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            closed.set(true);
            dead.set(true);
            throw McpException.connectionFailed("启动子进程失败：" + e.getMessage());
        }
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        startReaderThread();
        startStderrDrainThread();
    }

    /** stdout 读线程：逐行解码 → handler；EOF/异常置死并触发终止回调。 */
    private void startReaderThread() {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        messageHandler.accept(JsonRpcCodec.parse(line));
                    } catch (McpException e) {
                        // 单帧解析失败不终止读循环；交给协议层自行兜底
                    }
                }
            } catch (IOException e) {
                // 进程被销毁 / 管道关闭：视为正常终止
            }
            markDead();
        });
    }

    /** stderr 排空线程：防止子进程写满错误管道阻塞。 */
    private void startStderrDrainThread() {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // 仅排空
                }
            } catch (IOException ignored) {
                // 进程销毁时关闭，忽略
            }
        });
    }

    @Override
    public synchronized void send(JsonRpcMessage message) {
        if (closed.get() || process == null || !process.isAlive()) {
            throw McpException.connectionFailed("传输未存活");
        }
        try {
            stdin.write(JsonRpcCodec.serialize(message));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            markDead();
            throw McpException.connectionFailed("写入子进程失败：" + e.getMessage());
        }
    }

    @Override
    public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
        if (handler != null) {
            this.messageHandler = handler;
        }
    }

    @Override
    public void setTerminationHandler(Runnable handler) {
        if (handler != null) {
            this.terminationHandler = handler;
        }
    }

    @Override
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive() && !closed.get() && !dead.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Process p = process;
        if (p == null) {
            markDead();
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(DESTROY_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        markDead();
    }

    /** 置死并触发终止回调（恰好一次）。 */
    private void markDead() {
        if (dead.compareAndSet(false, true)) {
            terminationHandler.run();
        }
    }

    /** Windows 下对非原生二进制（.cmd/.bat/裸 npx 等）经 cmd /c 包装，.exe/.com 直启。 */
    static List<String> buildCommand(List<String> command) {
        return buildCommand(System.getProperty("os.name"), command);
    }

    static List<String> buildCommand(String osName, List<String> command) {
        if (!ProcessEnv.isWindows(osName) || command == null || command.isEmpty()) {
            return command;
        }
        String executable = command.get(0);
        if (isNativeExecutable(executable)) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(command.size() + 2);
        wrapped.add("cmd");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    private static boolean isNativeExecutable(String executable) {
        String lower = executable.toLowerCase();
        return lower.endsWith(".exe") || lower.endsWith(".com");
    }
}
