package com.acode.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanWriterTest {

    private final PlanWriter writer = new PlanWriter();

    @TempDir
    Path tempDir;

    @Test
    void savesPlanIntoPlansDirectoryWithSlugName() throws Exception {
        String content = "# 迁移方案\n第一步先做 X";
        Path file = writer.savePlan(tempDir, content);

        assertTrue(file.startsWith(tempDir.resolve(".acode/plans")));
        assertEquals("plan-迁移方案.md", file.getFileName().toString());
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void slugCleansSymbolsToHyphensAndTruncates() throws Exception {
        String content = "# 超长标题 一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十!! 结尾";
        Path file = writer.savePlan(tempDir, content);

        String name = file.getFileName().toString();
        assertTrue(name.startsWith("plan-") && name.endsWith(".md"));
        // slug 不含感叹号，且长度被截断（标题清洗后不超过 40 字符）
        String slug = name.substring("plan-".length(), name.length() - ".md".length());
        assertTrue(slug.length() <= 40);
        assertTrue(!slug.contains("!"));
    }

    @Test
    void emptyContentFallsBackToTimestampSlug() throws Exception {
        Path file = writer.savePlan(tempDir, "");

        String name = file.getFileName().toString();
        assertTrue(name.matches("plan-\\d{8}-\\d{6}\\.md"), "空内容应兜底时间戳，实际：" + name);
    }
}
