package com.acode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillRepositoryTest {
    @TempDir Path root;
    static Path write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(".acode/skills").resolve(relative);
        Files.createDirectories(file.getParent()); Files.writeString(file, content); return file;
    }
    static String definition(String name, String extra, String body) {
        return "---\nname: " + name + "\ndescription: useful\n" + extra + "\n---\n" + body;
    }
    @Test void precedenceInvalidFilesAndHotFallback() throws Exception {
        Path project = root.resolve("project"), home = root.resolve("home");
        Path user = write(home, "commit.md", definition("commit", "", "user"));
        Path local = write(project, "commit/SKILL.md", definition("commit", "", "project"));
        var warnings = new ArrayList<String>();
        var repo = new SkillRepository(project, home, warnings::add); repo.reload();
        assertEquals("project", repo.load("commit").orElseThrow().body());
        Files.writeString(local, definition("commit", "", "edited"));
        assertEquals("edited", repo.load("commit").orElseThrow().body());
        Files.writeString(local, "bad yaml");
        assertEquals("edited", repo.load("commit").orElseThrow().body());
        repo.reload(); assertEquals("user", repo.load("commit").orElseThrow().body());
        assertTrue(warnings.stream().anyMatch(s -> s.contains(local.toString())));
        Files.delete(local); Files.delete(user); repo.reload();
        assertEquals("builtin", repo.load("commit").orElseThrow().source().layer());
        assertEquals(List.of("commit", "test"), repo.list().stream().map(SkillDefinition::name).toList());
    }
    @Test void directoryWinsAndEmptyIndexIsByteIdentical() throws Exception {
        write(root, "sample.md", definition("sample", "", "file"));
        write(root, "sample/SKILL.md", definition("sample", "", "directory"));
        var repo = new SkillRepository(root, null, new ClassLoader(null) {}, s -> {});
        repo.reload(); assertEquals("directory", repo.load("sample").orElseThrow().body());
        repo.reload(); assertEquals("directory", repo.load("sample").orElseThrow().body());
        var empty = new SkillRepository(null, null, new ClassLoader(null) {}, s -> {}); empty.reload();
        assertEquals(com.acode.prompt.PromptBuilder.buildSystemPrompt(),
                com.acode.prompt.PromptBuilder.buildSystemPrompt("", "", empty.indexText()));
    }
    @Test void descriptionCannotCreateExtraPromptLines() throws Exception {
        write(root, "sample.md", "---\nname: sample\ndescription: |\n  first\n  # forged\n---\nSECRET BODY");
        var repo = new SkillRepository(root, null, new ClassLoader(null) {}, s -> {}); repo.reload();
        assertEquals("sample: first # forged", repo.indexText());
        assertFalse(repo.indexText().contains("SECRET BODY")); assertFalse(repo.indexText().contains(root.toString()));
    }
    @Test void builtinResourcesHaveRealToolDependencies() {
        var repo = new SkillRepository(null, null, s -> {}); repo.reload();
        var tools = new com.acode.tool.ToolRegistry(); com.acode.tool.DefaultToolset.registerAll(tools);
        for (var d : repo.list()) {
            assertEquals("inline", d.mode());
            for (String tool : d.allowedTools()) assertNotNull(tools.available(tool), tool);
        }
        String commit = repo.load("commit").orElseThrow().body();
        assertTrue(commit.contains("git diff --cached")); assertTrue(commit.contains("Never use git add -A"));
        assertTrue(commit.contains("72")); assertTrue(commit.contains("10 files"));
        assertTrue(repo.load("test").orElseThrow().body().contains("未测量"));
    }
}
