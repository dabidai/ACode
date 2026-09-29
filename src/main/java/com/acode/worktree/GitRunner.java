package com.acode.worktree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No shell, bounded output, closed stdin. Truncation is failure, never a clean status. */
public class GitRunner {
    private final Duration timeout;
    private final int limit;
    public GitRunner() { this(Duration.ofSeconds(120), 4 * 1024 * 1024); }
    public GitRunner(Duration timeout, int limit) { this.timeout = timeout; this.limit = limit; }
    public record Result(int code, String output) {
        public String require() throws IOException {
            if (code != 0) throw new IOException("Git 执行失败：" + output.strip());
            return output;
        }
    }
    public Result run(Path directory, String... args) throws IOException {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false"));
        command.addAll(List.of(args));
        return execute(directory, command);
    }
    public Result execute(Path directory, List<String> command) throws IOException {
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        // Inherited Git location overrides must not redirect operations to another repository.
        builder.environment().keySet().removeIf(k -> k.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        process.getOutputStream().close();
        var output = new ByteArrayOutputStream();
        var failure = new AtomicReference<IOException>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (output.size() + n > limit) {
                        failure.compareAndSet(null, new IOException("Git 输出超出限制"));
                    } else if (failure.get() == null) output.write(buffer, 0, n);
                }
            } catch (IOException e) { failure.compareAndSet(null, e); }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Git 执行超时");
            reader.join(timeout.toMillis());
            if (reader.isAlive()) throw new IOException("Git 输出读取超时");
            if (failure.get() != null) throw failure.get();
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git 执行已取消", e);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            reader.interrupt();
        }
    }
}
