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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GlobTool TOOL = new GlobTool();

    @TempDir
    Path tempDir;

    private ToolContext context() {
        return new ToolContext(tempDir);
    }

    private ObjectNode input(String pattern) {
        return JSON.createObjectNode().put("pattern", pattern);
    }

    @Test
    void doubleStarJavaMatchesNestedFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("a.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("sub/c.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("sub/d.txt"), "x", StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("**/*.java"), context());
        assertTrue(r.isSuccess());
        assertEquals(3, r.output().lines().count());
        assertTrue(r.output().contains("a.java"));
        assertTrue(r.output().contains("b.java"));
        assertTrue(r.output().contains("c.java"));
        assertFalse(r.output().contains("d.txt"));
    }

    @Test
    void globAgainstExplicitBasePath() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("README.md"), "x", StandardCharsets.UTF_8);
        ObjectNode input = JSON.createObjectNode()
                .put("pattern", "**/*.java")
                .put("path", "src");
        ToolResult r = TOOL.execute(input, context());
        assertTrue(r.isSuccess());
        assertTrue(r.output().contains("App.java"));
        assertFalse(r.output().contains("README.md"));
    }

    @Test
    void globResultsTruncatedOverLimit() throws Exception {
        for (int i = 0; i < 205; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }
        ToolResult r = TOOL.execute(input("*.txt"), context());
        assertTrue(r.isSuccess());
        assertTrue(r.output().contains("已截断"));
        assertTrue(r.output().lines().count() <= 201, "200 条结果 + 1 行提示");
    }

    @Test
    void globNoMatchReturnsEmptySuccess() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x", StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("**/*.java"), context());
        assertTrue(r.isSuccess());
        assertEquals("", r.output());
    }

    @Test
    void missingBaseDirectoryReturnsFailure() {
        ToolResult r = TOOL.execute(input("**/*.java"), new ToolContext(tempDir.resolve("nope")));
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("nope"));
    }

    @Test
    void displaySummaryShowsMatchCount() throws Exception {
        Files.writeString(tempDir.resolve("a.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("b.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("c.java"), "x", StandardCharsets.UTF_8);
        ToolResult r = TOOL.execute(input("**/*.java"), context());
        assertTrue(r.isSuccess());
        assertEquals("返回 3 个匹配", r.display());
    }

    @Test
    void displaySummaryMarksTruncation() throws Exception {
        for (int i = 0; i < 205; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }
        ToolResult r = TOOL.execute(input("*.txt"), context());
        assertTrue(r.isSuccess());
        assertEquals("返回 200 个匹配（已截断）", r.display());
    }
}
