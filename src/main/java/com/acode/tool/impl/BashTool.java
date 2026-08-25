package com.acode.tool.impl;

import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行 shell 命令：Windows 上优先 Git Bash、回退系统默认 shell。
 * 支持超时杀进程（destroyForcibly）与超长输出截断；非 0 退出码带错误标记返回。
 */
public class BashTool extends BaseTool {

    public static final int MAX_OUTPUT_CHARS = 30000;
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final ShellDetector detector;

    public BashTool() {
        this(new ShellDetector());
    }

    public BashTool(ShellDetector detector) {
        super("Bash", buildDescription(detector), Permission.EXEC);
        this.detector = detector;
    }

    /** 描述文案按实际探测到的 shell 动态拼装：cmd 回退时如实声明，避免模型误发 Unix 命令 */
    private static String buildDescription(ShellDetector detector) {
        String shellClause = "git-bash".equals(detector.shellName())
                ? "命令在 Git Bash 下以 Unix 风格运行；"
                : "命令在 cmd 下以 Windows 命令风格运行；";
        return "执行 shell 命令（Windows 优先 Git Bash，回退系统默认 shell）。"
                + "仅当没有专用工具覆盖时才用：读文件用 ReadFile、写文件用 WriteFile、改文件用 EditFile、"
                + "按名查找/列文件用 Glob、搜内容用 Grep。" + shellClause
                + "非 0 退出码返回失败并附输出。缺省 60 秒超时可用 timeout_ms 调整，输出超 30000 字符截断。";
    }

    public String shellName() {
        return detector.shellName();
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("command", ParamSpec.Type.STRING,
                        "要执行的 shell 命令（写清命令用途，便于他人理解与审查）"),
                ParamSpec.optional("timeout_ms", ParamSpec.Type.INTEGER,
                        "超时上限（毫秒），缺省 60000"));
    }

    @Override
    protected long defaultTimeoutMillis() {
        return DEFAULT_TIMEOUT_MS;
    }

    /** 外壳超时取 max(默认, timeout_ms)：允许参数延长到默认值以上，不被外壳静默截断 */
    @Override
    protected long timeoutMillis(JsonNode input) {
        long requested = requestedTimeoutMillis(input);
        return requested > 0 ? Math.max(defaultTimeoutMillis(), requested) : defaultTimeoutMillis();
    }

    /** 从请求参数提取 timeout_ms：正数时返回，否则 -1（无覆盖） */
    private static long requestedTimeoutMillis(JsonNode input) {
        if (input != null && input.has("timeout_ms") && input.get("timeout_ms").isIntegralNumber()) {
            long t = input.get("timeout_ms").asLong();
            if (t > 0) {
                return t;
            }
        }
        return -1;
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        String command = input.get("command").asText();
        long timeout = input.has("timeout_ms") ? input.get("timeout_ms").asLong() : defaultTimeoutMillis();
        if (timeout <= 0) {
            return ToolResult.failure("timeout_ms 必须为正数");
        }

        List<String> full = new ArrayList<>(detector.commandPrefix());
        full.add(command);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.directory(context.workingDirectory().toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ToolResult.failure("启动命令失败：" + e.getMessage());
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    buffer.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // 进程被强杀时输入流可能提前关闭，忽略
            }
        });
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return ToolResult.failure("命令执行超时（上限 " + timeout + " ms），进程已被终止");
            }
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResult.failure("命令执行被中断");
        }

        String output = truncate(buffer.toString(StandardCharsets.UTF_8));
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            return ToolResult.failure("命令退出码 " + exitCode + "：\n" + output);
        }
        return ToolResult.success(output);
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n…（输出过长，已截断）";
    }
}
