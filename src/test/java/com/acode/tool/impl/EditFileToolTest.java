package com.acode.tool.impl;

import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EditFileTool TOOL = new EditFileTool();

    @TempDir
    Path tempDir;

    private ToolContext context() {
        return new ToolContext(tempDir);
    }

    private static ObjectNode edit(String old, String replacement) {
        return JSON.createObjectNode().put("old", old).put("new", replacement);
    }

    private ObjectNode input(Path file, ObjectNode... edits) {
        ArrayNode arr = JSON.createArrayNode();
        for (ObjectNode e : edits) {
            arr.add(e);
        }
        return JSON.createObjectNode().put("file_path", file.toString()).set("edits", arr);
    }

    @Test
    void multipleEditsAllApplyInOrder() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello foo bar end", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, edit("foo", "X"), edit("bar", "Y")), context());
        assertTrue(result.isSuccess());
        assertEquals("hello X Y end", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void unmatchedSegmentFailsWholeOperationWithoutChangingFile() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "alpha beta", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        ToolResult result = TOOL.execute(input(file, edit("alpha", "A"), edit("zzz", "Z")), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("未找到匹配内容"));
        assertArrayEquals(before, Files.readAllBytes(file), "失败后文件字节必须完全不变");
    }

    @Test
    void ambiguousOldStringFailsWithNotUnique() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "x x x", StandardCharsets.UTF_8);
        ToolResult result = TOOL.execute(input(file, edit("x", "y")), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("不唯一"), "错误文本应含「不唯一」");
        assertEquals("x x x", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void secondEditTargetsTextIntroducedByFirstEdit() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "old1", StandardCharsets.UTF_8);
        // 先 old1→middle，再 middle→final，验证按段顺序逐步应用
        ToolResult result = TOOL.execute(input(file, edit("old1", "middle"), edit("middle", "final")),
                context());
        assertTrue(result.isSuccess());
        assertEquals("final", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void missingFileReturnsFailure() {
        Path file = tempDir.resolve("nope.txt");
        ToolResult result = TOOL.execute(input(file, edit("a", "b")), context());
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains(file.toString()));
    }
}
