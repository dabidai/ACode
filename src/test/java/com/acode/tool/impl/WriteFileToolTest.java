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
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Test
    void displayNewFileShowsAllPlusLines() throws Exception {
        Path file = tempDir.resolve("new.txt");
        ToolResult result = TOOL.execute(input(file, "第一行\n第二行"), context());
        assertTrue(result.isSuccess());
        assertTrue(result.display().startsWith("已写入 " + file + "（"), "display 首行为确认文案");
        assertTrue(result.display().contains("\n+ 第一行"));
        assertTrue(result.display().contains("\n+ 第二行"));
        assertEquals(3, result.display().lines().count(), "确认行 + 2 个 + 行");
        assertEquals("已写入 " + file + "（7 字符）", result.output(), "output 为原确认文案，不含 diff");
    }

    @Test
    void displayOverwriteShowsMinusAndPlus() throws Exception {
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "第一行\n第二行\n第三行", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, "第一行\n改的行\n第三行"), context());
        assertTrue(result.isSuccess());
        assertTrue(result.display().contains("\n- 第二行"));
        assertTrue(result.display().contains("\n+ 改的行"));
        assertEquals("第一行\n改的行\n第三行", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void displayIdenticalContentHasNoDiffLines() throws Exception {
        Path file = tempDir.resolve("same.txt");
        String content = "a\nb\nc";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, content), context());
        assertTrue(result.isSuccess());
        assertEquals(1, result.display().lines().count(), "相同内容只有确认行，无 diff 行");
        assertFalse(result.display().contains("\n+ "));
        assertFalse(result.display().contains("\n- "));
    }

    @Test
    void displayHugeChangeIsOmitted() throws Exception {
        Path file = tempDir.resolve("big.txt");
        Files.writeString(file, "old-content", StandardCharsets.UTF_8);
        String content = IntStream.range(0, 400).mapToObj(i -> "line-" + i)
                .collect(Collectors.joining("\n"));
        ToolResult result = TOOL.execute(input(file, content), context());
        assertTrue(result.isSuccess());
        assertTrue(result.display().endsWith("…（变化过大，省略对比）"));
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8), "diff 省略不影响写入");
    }

    @Test
    void displayUnreadableOldContentStillWrites() throws Exception {
        Path file = tempDir.resolve("bad.txt");
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28}); // 非法 UTF-8 序列
        ToolResult result = TOOL.execute(input(file, "新内容"), context());
        assertTrue(result.isSuccess(), "旧内容读取失败不应阻断写入");
        assertEquals("新内容", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(result.display().contains("旧内容读取失败，省略对比"));
    }

    @Test
    void displayOversizedOldContentSkipped() throws Exception {
        Path file = tempDir.resolve("huge.txt");
        byte[] big = new byte[(int) WriteFileTool.MAX_OLD_FILE_BYTES + 1024];
        Arrays.fill(big, (byte) 'x');
        Files.write(file, big);
        ToolResult result = TOOL.execute(input(file, "新内容"), context());
        assertTrue(result.isSuccess());
        assertEquals("新内容", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(result.display().contains("旧内容过大，省略对比"));
    }
}
