package com.acode.hook;

import com.acode.tool.impl.ShellDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;

/** External action execution. Permission checks belong to HookEngine, before this boundary. */
public final class HookActions implements HookAction {
    private static final Logger log = LoggerFactory.getLogger(HookActions.class);
    private final Path root;
    public HookActions(Path root) { this.root = root; }

    @Override public Result execute(HookConfig hook, HookContext context) throws Exception {
        return switch (hook.action().type()) {
            case PROMPT -> Result.success(context.expand(hook.action().value("message")));
            case COMMAND -> command(hook, context);
            case HTTP -> http(hook, context);
            case AGENT -> {
                log.warn("Hook agent 执行器未实现（待 ch12 SubAgent 运行时对接）[{}]", hook.id());
                yield Result.failure("agent 执行器未实现");
            }
        };
    }
    private Result command(HookConfig hook, HookContext context) throws Exception {
        var command = new ArrayList<>(new ShellDetector().commandPrefix());
        command.add(context.expand(hook.action().value("command")));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        FutureTask<String> output = new FutureTask<>(() -> readBounded(process.getInputStream(), 30_000, true));
        Thread reader = Thread.ofVirtual().name("acode-hook-output").start(output);
        try {
            if (!process.waitFor(hook.action().timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Hook 命令超时 [{}]：{}", hook.id(), timeoutLabel(hook));
                return Result.failure("命令超时");
            }
            String text = output.get(hook.action().timeout().toMillis(), TimeUnit.MILLISECONDS);
            int code = process.exitValue();
            if (code != 0) log.warn("Hook 命令非零退出 [{}]：exit={}", hook.id(), code);
            return new Result(code == 0, text);
        } finally {
            terminate(process);
            process.getInputStream().close();
            reader.interrupt();
        }
    }
    private static void terminate(Process process) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            for (ProcessHandle child : descendants) {
                try { child.onExit().get(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException | TimeoutException ignored) { }
            }
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private String timeoutLabel(HookConfig hook) {
        long ms = hook.action().timeout().toMillis();
        return ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }
    private Result http(HookConfig hook, HookContext context) throws Exception {
        var action = hook.action();
        URI uri = URI.create(context.expand(action.value("url")));
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            return Result.failure("HTTP URL 必须使用 http 或 https");
        // Never follow redirects: each new destination requires its own permission decision.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(action.timeout()).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(uri).timeout(action.timeout())
                    .method(action.values().getOrDefault("method", "POST"), HttpRequest.BodyPublishers.ofString(context.expand(action.value("body")))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // The response body has its own deadline; request timeout alone does not cover streaming bodies.
            try (InputStream body = response.body()) {
                FutureTask<String> read = new FutureTask<>(() -> readBounded(body, 4000, false));
                Thread thread = Thread.ofVirtual().start(read);
                try {
                    return new Result(response.statusCode() >= 200 && response.statusCode() < 300,
                            read.get(action.timeout().toMillis(), TimeUnit.MILLISECONDS));
                } finally { thread.interrupt(); }
            }
        }
    }
    private static String readBounded(InputStream input, int limit, boolean drain) throws IOException {
        var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[4096];
        boolean truncated = false;
        int n;
        while ((n = reader.read(buffer)) != -1) {
            int keep = Math.min(n, limit - out.length());
            out.append(buffer, 0, keep);
            truncated |= keep < n;
            if (!drain && out.length() >= limit) break;
        }
        return out + (truncated && drain ? "\n…（输出过长，已截断）" : "");
    }
}
