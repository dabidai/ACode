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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReadFileTool TOOL = new ReadFileTool();

    @TempDir
    Path tempDir;

    private ToolContext context(Path workdir) {
        return new ToolContext(workdir);
    }

    private ObjectNode params(Path file) {
        return JSON.createObjectNode().put("file_path", file.toString());
    }

    @Test
    void readsExistingTextFileMatchingDisk() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "第一行\n第二行\n", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(params(file), context(tempDir));
        assertTrue(result.isSuccess());
        assertEquals("第一行\n第二行\n", result.output());
    }

    @Test
    void readingMissingFileReturnsFailureWithPath() {
        Path missing = tempDir.resolve("nope.txt");
        ToolResult result = TOOL.execute(params(missing), context(tempDir));
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains(missing.toString()), "错误文本应含文件路径");
    }

    @Test
    void largeFileTruncatedAtMaxLines() throws Exception {
        Path file = tempDir.resolve("big.txt");
        String content = IntStream.range(0, 3000).mapToObj(i -> "line-" + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(file, content, StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(params(file), context(tempDir));
        assertTrue(result.isSuccess());
        assertTrue(result.output().contains("line-0"), "应含开头行");
        assertTrue(result.output().contains("已截断"), "应附截断提示");
        assertFalse(result.output().contains("line-2000"), "第 2001 行不应返回");
    }

    @Test
    void offsetAndLimitReturnRange() throws Exception {
        Path file = tempDir.resolve("range.txt");
        String content = IntStream.range(0, 10).mapToObj(i -> "row-" + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(file, content, StandardCharsets.UTF_8);
        ObjectNode input = params(file).put("offset", 2).put("limit", 3);
        ToolResult result = TOOL.execute(input, context(tempDir));
        assertTrue(result.isSuccess());
        assertEquals("row-2\nrow-3\nrow-4\n", result.output());
    }

    @Test
    void relativePathResolvedAgainstWorkingDirectory() throws Exception {
        Path sub = tempDir.resolve("sub");
        Files.createDirectories(sub);
        Path file = sub.resolve("x.txt");
        Files.writeString(file, "相对路径内容", StandardCharsets.UTF_8);
        ObjectNode input = JSON.createObjectNode().put("file_path", "sub/x.txt");
        ToolResult result = TOOL.execute(input, context(tempDir));
        assertTrue(result.isSuccess());
        assertEquals("相对路径内容\n", result.output());
    }
}
