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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GrepTool TOOL = new GrepTool();

    @TempDir
    Path tempDir;

    private ToolContext context() {
        return new ToolContext(tempDir);
    }

    private ObjectNode input(String pattern) {
        return JSON.createObjectNode().put("pattern", pattern);
    }

    @Test
    void regexHitIncludesPathLineNumberAndContent() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"),
                "public class App {\n  public static void main() {}\n}\n", StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("static"), context());
        assertTrue(r.isSuccess());
        String hit = r.output().lines().filter(l -> l.contains("App.java"))
                .findFirst().orElseThrow(() -> new AssertionError("未命中 App.java"));
        assertTrue(hit.contains(":2:"), "命中行应含行号，实际：" + hit);
        assertTrue(hit.contains("public static void main"), "命中行应含行内容");
    }

    @Test
    void noHitReturnsEmptySuccess() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello world", StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("nomatchxyz"), context());
        assertTrue(r.isSuccess());
        assertEquals("", r.output());
    }

    @Test
    void includeFilterLimitsFiles() throws Exception {
        Files.writeString(tempDir.resolve("a.java"), "foo", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.txt"), "foo", StandardCharsets.UTF_8);
        ObjectNode input = JSON.createObjectNode()
                .put("pattern", "foo")
                .put("include", "*.java");
        ToolResult r = TOOL.execute(input, context());
        assertTrue(r.isSuccess());
        assertTrue(r.output().contains("a.java"));
        assertFalse(r.output().contains("b.txt"));
    }

    @Test
    void hitsTruncatedOverLimit() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            lines.add("match" + i);
        }
        Files.write(tempDir.resolve("big.txt"), lines, StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("match"), context());
        assertTrue(r.isSuccess());
        assertTrue(r.output().contains("已截断"));
        assertTrue(r.output().lines().count() <= 501, "500 条命中 + 1 行提示");
    }

    @Test
    void invalidRegexReturnsFailure() {
        ToolResult r = TOOL.execute(input("("), context());
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("正则"));
    }

    @Test
    void missingBaseDirectoryReturnsFailure() {
        ToolResult r = TOOL.execute(input("foo"), new ToolContext(tempDir.resolve("nope")));
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("nope"));
    }
}
