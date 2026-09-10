package com.acode.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInstructionsTest {

    @TempDir
    Path root;

    private Path projectRoot;
    private Path userHome;

    @BeforeEach
    void setUp() {
        projectRoot = root.resolve("proj");
        userHome = root.resolve("home");
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void loadsThreeLayersWithProjectFirst() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "TEAM_RULES");
        write(projectRoot.resolve(".acode").resolve("ACODE.md"), "LOCAL_PREFS");
        write(userHome.resolve(".acode").resolve("ACODE.md"), "GLOBAL_PREFS");

        var result = ProjectInstructions.load(projectRoot, userHome);
        int team = result.text().indexOf("TEAM_RULES");
        int local = result.text().indexOf("LOCAL_PREFS");
        int global = result.text().indexOf("GLOBAL_PREFS");
        assertTrue(team >= 0 && local >= 0 && global >= 0, result.text());
        assertTrue(team < local && local < global, "project layers before user layer");
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void joinsLayersWithFixedSeparator() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "AAA");
        write(projectRoot.resolve(".acode").resolve("ACODE.md"), "BBB");
        assertEquals("AAA\n\n----\n\nBBB", ProjectInstructions.load(projectRoot, userHome).text());
    }

    @Test
    void skipsMissingLayers() throws IOException {
        write(userHome.resolve(".acode").resolve("ACODE.md"), "ONLY_GLOBAL");
        var result = ProjectInstructions.load(projectRoot, userHome);
        assertEquals("ONLY_GLOBAL", result.text());
        assertFalse(result.text().contains("----"), "no separator when only one layer present");
    }

    @Test
    void returnsEmptyWhenAllLayersMissing() {
        var result = ProjectInstructions.load(projectRoot, userHome);
        assertEquals("", result.text());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void malformedLayerDegradesToWarningWithoutBreakingStartup() throws IOException {
        write(projectRoot.resolve(".acode").resolve("ACODE.md"), "LOCAL_OK");
        // 非法 UTF-8 字节：该层读取必然失败 → 跳过 + 告警，其余层照常加载
        Files.write(projectRoot.resolve("ACODE.md"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF});

        var result = ProjectInstructions.load(projectRoot, userHome);

        assertEquals("LOCAL_OK", result.text(), "畸形层被跳过后其余层照常拼接");
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("不可读"), result.warnings().toString());
    }

    @Test
    void expandsIncludesPerLayerAndReportsWarnings() throws IOException {
        write(projectRoot.resolve("rules.md"), "INCLUDED_RULES");
        // 项目根之外但真实存在：越界判定必须命中"超出项目范围"，而不是"目标不存在"
        write(root.resolve("escaped.md"), "ESCAPED_BODY");
        write(projectRoot.resolve("ACODE.md"), "HEAD\n@include rules.md\n@include ../escaped.md");

        var result = ProjectInstructions.load(projectRoot, userHome);
        assertTrue(result.text().contains("INCLUDED_RULES"), result.text());
        assertTrue(result.text().startsWith("HEAD"), result.text());
        assertFalse(result.text().contains("ESCAPED_BODY"));
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).contains("超出项目范围"), result.warnings().toString());
    }
}
