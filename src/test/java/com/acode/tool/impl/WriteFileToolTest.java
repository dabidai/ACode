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

class WriteFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WriteFileTool TOOL = new WriteFileTool();

    @TempDir
    Path tempDir;

    private ToolContext context() {
        return new ToolContext(tempDir);
    }

    private ObjectNode input(Path file, String content) {
        return JSON.createObjectNode().put("file_path", file.toString()).put("content", content);
    }

    @Test
    void overwritesExistingFileCompletely() throws Exception {
        Path file = tempDir.resolve("f.txt");
        Files.writeString(file, "旧内容", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, "新内容"), context());
        assertTrue(result.isSuccess());
        assertEquals("新内容", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void autoCreatesMissingParentDirectories() throws Exception {
        Path file = tempDir.resolve("nested").resolve("deep").resolve("f.txt");
        ToolResult result = TOOL.execute(input(file, "hello"), context());
        assertTrue(result.isSuccess());
        assertTrue(Files.isRegularFile(file), "文件应已创建");
        assertTrue(Files.isDirectory(file.getParent()), "缺失的父目录应被自动创建");
        assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void relativePathResolvedAgainstWorkingDirectory() throws Exception {
        ToolResult result = TOOL.execute(
                JSON.createObjectNode().put("file_path", "rel.txt").put("content", "相对"),
                context());
        assertTrue(result.isSuccess());
        assertTrue(Files.isRegularFile(tempDir.resolve("rel.txt")));
        assertEquals("相对", Files.readString(tempDir.resolve("rel.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void writingToDirectoryPathReturnsFailure() throws Exception {
        Path dir = tempDir.resolve("adir");
        Files.createDirectories(dir);
        ToolResult result = TOOL.execute(input(dir, "x"), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains(dir.toString()), "错误文本应含路径");
    }

    @Test
    void emptyContentOverwritesWithEmpty() throws Exception {
        Path file = tempDir.resolve("e.txt");
        Files.writeString(file, "旧", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, ""), context());
        assertTrue(result.isSuccess());
        assertTrue(Files.isRegularFile(file));
        assertEquals("", Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("旧"));
    }
}
