package com.acode.tool.impl;

import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
