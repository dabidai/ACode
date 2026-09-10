package com.acode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileTest {

    @TempDir
    Path tempDir;

    private static final String WELL_FORMED = """
            ---
            name: deadline
            description: 数据库迁移工具选型
            type: project
            ---

            本项目用 golang-migrate。
            **Why**：…
            **How to apply**：…
            """;

    @Test
    void roundTripsHeaderAndBody() {
        MemoryFile parsed = MemoryFile.parse(WELL_FORMED).orElseThrow();
        assertEquals(MemoryType.PROJECT, parsed.type());
        assertEquals("deadline", parsed.name());
        assertEquals("数据库迁移工具选型", parsed.description());
        assertTrue(parsed.body().contains("**Why**："));
        assertTrue(parsed.body().contains("**How to apply**："));
        assertEquals("project-deadline.md", parsed.fileName());
    }

    @Test
    void renderProducesParseableText() {
        MemoryFile memory = new MemoryFile(MemoryType.FEEDBACK, "testing", "摘要", "正文");
        MemoryFile reparsed = MemoryFile.parse(MemoryFile.render(memory)).orElseThrow();
        assertEquals(memory, reparsed);
    }

    @Test
    void readsFromDisk() throws IOException {
        Path file = tempDir.resolve("user-any.md");
        Files.writeString(file, WELL_FORMED.replace("type: project", "type: user")
                .replace("name: deadline", "name: any"), StandardCharsets.UTF_8);
        assertEquals(MemoryType.USER, MemoryFile.read(file).orElseThrow().type());
        assertTrue(MemoryFile.read(tempDir.resolve("missing.md")).isEmpty());
    }

    @Test
    void rejectsMalformedHeaders() {
        String[] rejected = {
                "没有 frontmatter",
                "---\ndescription: 缺 name 与 type\n---\n正文",
                "---\nname: x\ndescription: 缺 type\n---\n正文",
                "---\nname: x\ntype: project\n---\n正文",
                "---\nname: x\ndescription: y\ntype: project\n", // 没有闭合围栏
        };
        for (String content : rejected) {
            assertTrue(MemoryFile.parse(content).isEmpty(), content);
        }
    }

    @Test
    void rejectsUnknownTypeSlug() {
        Optional<MemoryFile> parsed = MemoryFile.parse(WELL_FORMED.replace("type: project", "type: unknown"));
        assertTrue(parsed.isEmpty(), "type 非四类之一应被拒绝");
    }

    @Test
    void rejectsNonSlugNames() {
        String[] names = {"UPPER", "has space", "has/slash", "..", "-leading-dash", "a".repeat(41)};
        for (String name : names) {
            String content = WELL_FORMED.replace("name: deadline", "name: " + name);
            assertTrue(MemoryFile.parse(content).isEmpty(), "非法 name 应被拒绝：" + name);
        }
    }

    @Test
    void toleratesNameWrittenAsFileName() {
        String content = WELL_FORMED.replace("name: deadline", "name: project-deadline.md");
        MemoryFile parsed = MemoryFile.parse(content).orElseThrow();
        assertEquals("deadline", parsed.name());
        assertEquals("project-deadline.md", parsed.fileName());
    }

    @Test
    void toleratesCarriageReturns() {
        assertTrue(MemoryFile.parse(WELL_FORMED.replace("\n", "\r\n")).isPresent());
    }
}
