package com.acode.tool.impl;

import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ToolContext context() {
        return new ToolContext(tempDir);
    }

    private ObjectNode input(String command) {
        return JSON.createObjectNode().put("command", command);
    }

    @Test
    void echoReturnsOutput() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(input("echo hello"), context());
        assertTrue(result.isSuccess());
        assertTrue(result.output().contains("hello"), "输出应含 hello，实际：" + result.output());
    }

    @Test
    void nonZeroExitCodeMarkedAsError() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(input("exit 3"), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("3"), "错误文本应含退出码，实际：" + result.errorMessage());
    }

    @Test
    void sleepWithShortTimeoutIsKilled() {
        BashTool tool = new BashTool();
        // sleep 仅 Git Bash 有；cmd 用 ping 制造长时间等待。
        // 工作目录用系统临时目录而非 @TempDir：被强杀进程会短暂持有 CWD 句柄，阻碍清理
        String command = tool.shellName().equals("git-bash")
                ? "sleep 5"
                : "ping -n 6 127.0.0.1";
        ToolContext ctx = new ToolContext(Path.of(System.getProperty("java.io.tmpdir")));
        ObjectNode in = input(command).put("timeout_ms", 1000);
        long start = System.currentTimeMillis();
        ToolResult result = tool.execute(in, ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("超时"), "错误文本应含「超时」：" + result.errorMessage());
        assertTrue(elapsed < 5000, "应在超时点附近返回，实际耗时 " + elapsed + " ms");
    }

    @Test
    void hugeOutputTruncated() throws Exception {
        BashTool tool = new BashTool();
        Path big = tempDir.resolve("big.txt");
        Files.writeString(big, "x".repeat(50000), StandardCharsets.UTF_8);
        String command = tool.shellName().equals("git-bash")
                ? "cat \"" + big + "\""
                : "type \"" + big + "\"";
        ToolResult result = tool.execute(input(command), context());
        assertTrue(result.isSuccess());
        assertTrue(result.output().contains("输出过长"), "应附输出过长提示");
        assertTrue(result.output().length() < 50000, "输出应被截断");
    }

    @Test
    void invalidTimeoutRejected() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(input("echo hi").put("timeout_ms", -1), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("timeout_ms"));
    }

    /**
     * 契约：timeout_ms 覆盖应生效（描述文案承诺「缺省 60 秒超时可用 timeout_ms 调整」，
     * 即允许延长到 60s 以上）。当前 BaseTool 外壳固定用 defaultTimeoutMillis() 掐表，
     * timeout_ms 超过默认值即被静默截断。测试把外壳默认压到 400ms 加速复现：
     * timeout_ms=10000 + 约 2 秒的命令应成功，实际在 400ms 处被外壳超时杀死。
     */
    @Disabled("待修复：BaseTool.execute 外壳固定用 defaultTimeoutMillis() 超时，BashTool timeout_ms 超过 60s 被静默截断")
    @Test
    void timeoutShellHonorsToolOverrideBeyondDefault() throws Exception {
        BashTool tool = new BashTool() {
            @Override
            protected long defaultTimeoutMillis() {
                return 400; // 测试加速：把外壳默认超时压到 400ms
            }
        };
        // sleep 仅 Git Bash 有；cmd 用 ping 制造约 2 秒等待
        String command = tool.shellName().equals("git-bash")
                ? "sleep 2"
                : "ping -n 3 127.0.0.1";
        // 工作目录用系统临时目录而非 @TempDir：被强杀进程会短暂持有 CWD 句柄，阻碍清理
        ToolContext ctx = new ToolContext(Path.of(System.getProperty("java.io.tmpdir")));
        ObjectNode in = input(command).put("timeout_ms", 10000);
        long start = System.currentTimeMillis();
        ToolResult result = tool.execute(in, ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.isSuccess(),
                "timeout_ms=10000 应生效（命令约 2s 内完成），实际错误：" + result.errorMessage());
        assertTrue(elapsed < 5000, "成功路径应远快于外壳默认超时，实际耗时 " + elapsed + " ms");
    }

    @Test
    void descriptionMentionsGitBashWhenDetected() throws Exception {
        Path fakeBash = tempDir.resolve("bash.exe");
        Files.createFile(fakeBash);
        BashTool tool = new BashTool(new ShellDetector(List.of(fakeBash.toString())));
        assertTrue(tool.description().contains("Git Bash"),
                "检测到 Git Bash 时描述应声称 Unix 风格，实际：" + tool.description());
    }

    @Test
    void cmdFallbackDescriptionDoesNotClaimGitBash() throws Exception {
        BashTool tool = new BashTool(new ShellDetector(
                List.of(tempDir.resolve("nope").resolve("bash.exe").toString())));
        assertTrue(tool.description().contains("cmd"), "cmd 回退时描述应说明 cmd，实际：" + tool.description());
        assertTrue(!tool.description().contains("Unix 风格"),
                "cmd 回退时不应承诺 Unix 命令风格，实际：" + tool.description());
    }
}
