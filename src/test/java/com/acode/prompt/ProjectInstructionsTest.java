package com.acode.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    // ---- 三层定义与状态查询（/memory 菜单共用） ----

    @Test
    void layersExposeSingleSourceDefinitions() {
        var layers = ProjectInstructions.layers(projectRoot, userHome);

        assertEquals(3, layers.size());
        assertEquals(projectRoot.resolve("ACODE.md"), layers.get(0).file());
        assertEquals(projectRoot.resolve(".acode").resolve("ACODE.md"), layers.get(1).file());
        assertEquals(userHome.resolve(".acode").resolve("ACODE.md"), layers.get(2).file());
        assertEquals(List.of("项目指令", "本地指令", "用户指令"),
                layers.stream().map(ProjectInstructions.Layer::menuLabel).toList());
        assertEquals(List.of("项目", "项目", "用户主目录"),
                layers.stream().map(ProjectInstructions.Layer::boundaryLabel).toList());
    }

    @Test
    void layerStateReportsPathLabelExistsAndLineCount() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "L1\nL2\nL3");
        write(userHome.resolve(".acode").resolve("ACODE.md"), "ONLY_ONE");

        var states = ProjectInstructions.layers(projectRoot, userHome).stream()
                .map(ProjectInstructions.Layer::state).toList();

        assertEquals(projectRoot.resolve("ACODE.md"), states.get(0).path());
        assertEquals("项目指令", states.get(0).menuLabel());
        assertTrue(states.get(0).exists());
        assertEquals(3, states.get(0).lines());
        assertFalse(states.get(1).exists());
        assertEquals("用户指令", states.get(2).menuLabel());
        assertEquals(1, states.get(2).lines());
    }

    @Test
    void emptyFileCountsAsZeroLinesAndMissingLayerDoesNotThrow() throws IOException {
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("ACODE.md"), "", StandardCharsets.UTF_8);

        var states = ProjectInstructions.layers(projectRoot, userHome).stream()
                .map(ProjectInstructions.Layer::state).toList();

        assertTrue(states.get(0).exists());
        assertEquals(0, states.get(0).lines());
        assertFalse(states.get(1).exists(), "本地指令层缺失：不抛错，标记不存在");
        assertEquals(0, states.get(1).lines());
        assertFalse(states.get(2).exists());
        assertEquals(0, states.get(2).lines());
    }

    @Test
    void createEmptyCreatesMissingLayerWithParentDirs() {
        var local = ProjectInstructions.layers(projectRoot, userHome).get(1);
        assertFalse(Files.exists(local.file()));

        assertTrue(local.createEmpty());
        assertTrue(Files.isRegularFile(local.file()));
        assertEquals(0, local.state().lines());
    }

    @Test
    void createEmptyKeepsExistingContent() throws IOException {
        write(projectRoot.resolve("ACODE.md"), "KEEP_ME");
        var projectLayer = ProjectInstructions.layers(projectRoot, userHome).get(0);

        assertTrue(projectLayer.createEmpty());
        assertEquals("KEEP_ME", Files.readString(projectLayer.file(), StandardCharsets.UTF_8));
    }
}
