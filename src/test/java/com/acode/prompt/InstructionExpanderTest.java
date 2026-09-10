package com.acode.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstructionExpanderTest {

    @TempDir
    Path root;

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String expandTop(String relative) {
        var expander = new InstructionExpander();
        return expander.expand(root.resolve(relative), root, "项目");
    }

    @Test
    void expandsIncludeInPlace() throws IOException {
        write("part.md", "PART_BODY");
        Path top = write("ACODE.md", "before\n@include part.md\nafter");
        assertEquals("before\nPART_BODY\nafter", expandTop("ACODE.md"));
        assertTrue(Files.exists(top));
    }

    @Test
    void relativePathResolvesAgainstIncludingFileDirectory() throws IOException {
        write("nested/part.md", "NESTED_BODY");
        write("nested/mid.md", "@include part.md");
        // 顶层引用 nested/mid.md；其中的 part.md 必须相对 nested/ 解析，而非项目根
        write("ACODE.md", "@include nested/mid.md");
        assertEquals("NESTED_BODY", expandTop("ACODE.md"));
    }

    @Test
    void stopsAtDepthLimit() throws IOException {
        write("f7.md", "MARKER_7");
        write("f6.md", "MARKER_6\n@include f7.md");
        write("f5.md", "MARKER_5\n@include f6.md");
        write("f4.md", "MARKER_4\n@include f5.md");
        write("f3.md", "MARKER_3\n@include f4.md");
        write("f2.md", "MARKER_2\n@include f3.md");
        write("f1.md", "MARKER_1\n@include f2.md");

        String result = expandTop("f1.md");
        assertTrue(result.contains("MARKER_1"), "depth 1 expanded");
        assertTrue(result.contains("MARKER_5"), "depth 5 expanded (top + 4)");
        assertFalse(result.contains("MARKER_6"), "depth 6 not expanded");
        assertFalse(result.contains("MARKER_7"), "beyond limit not expanded");
    }

    @Test
    void mutualIncludeDoesNotRecurseForever() throws IOException {
        write("a.md", "BODY_A\n@include b.md");
        write("b.md", "BODY_B\n@include a.md");
        String result = expandTop("a.md");
        assertTrue(result.contains("BODY_A"));
        assertTrue(result.contains("BODY_B"));
        assertEquals(1, countOccurrences(result, "BODY_A"));
        assertEquals(1, countOccurrences(result, "BODY_B"));
    }

    @Test
    void sameFileReferencedTwiceExpandsOnce() throws IOException {
        write("shared.md", "SHARED_BODY");
        write("ACODE.md", "@include shared.md\n@include shared.md");
        assertEquals(1, countOccurrences(expandTop("ACODE.md"), "SHARED_BODY"));
    }

    @Test
    void rejectsEscapeFromProjectRoot() throws IOException {
        Path outside = root.getParent().resolve("outside-" + root.getFileName() + ".md");
        Files.writeString(outside, "OUTSIDE_BODY", StandardCharsets.UTF_8);
        write("ACODE.md", "@include ../" + outside.getFileName());

        var expander = new InstructionExpander();
        String result = expander.expand(root.resolve("ACODE.md"), root, "项目");
        assertFalse(result.contains("OUTSIDE_BODY"), "escaped include not expanded");
        assertTrue(expander.warnings().stream().anyMatch(w -> w.contains("超出项目范围")),
                "warning mentions project boundary: " + expander.warnings());
        Files.deleteIfExists(outside);
    }

    @Test
    void rejectsEscapeFromUserHome() throws IOException {
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve(".acode"));
        Path top = write("home/.acode/ACODE.md", "@include ../../victim.md");
        write("victim.md", "VICTIM_BODY");

        var expander = new InstructionExpander();
        String result = expander.expand(top, home, "用户主目录");
        assertFalse(result.contains("VICTIM_BODY"));
        assertTrue(expander.warnings().stream().anyMatch(w -> w.contains("超出用户主目录范围")),
                "warning mentions user-home boundary: " + expander.warnings());
    }

    @Test
    void rejectsSymlinkEscapeFromProjectRoot() throws IOException {
        Path outside = root.getParent().resolve("linked-" + root.getFileName() + ".md");
        Files.writeString(outside, "LINKED_BODY", StandardCharsets.UTF_8);
        Path link = root.resolve("linked.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlink creation unsupported on this platform");
        }
        write("ACODE.md", "@include linked.md");

        var expander = new InstructionExpander();
        String result = expander.expand(root.resolve("ACODE.md"), root, "项目");
        assertFalse(result.contains("LINKED_BODY"), "symlink escaping project root rejected");
        Files.deleteIfExists(link);
        Files.deleteIfExists(outside);
    }

    @Test
    void skipsMissingDirectoryAndUnreadableWithoutThrowing() throws IOException {
        Files.createDirectories(root.resolve("adir"));
        write("ACODE.md", "@include missing.md\n@include adir\nKEPT");
        var expander = new InstructionExpander();
        String result = expander.expand(root.resolve("ACODE.md"), root, "项目");
        assertEquals("KEPT", result);
        assertEquals(2, expander.warnings().size(), expander.warnings().toString());
    }

    @Test
    void truncatesOversizedFile() throws IOException {
        write("huge.md", "X".repeat(InstructionExpander.MAX_FILE_CHARS + 500));
        write("ACODE.md", "@include huge.md");
        String result = expandTop("ACODE.md");
        assertTrue(result.endsWith(InstructionExpander.TRUNCATION_MARKER));
        assertEquals(InstructionExpander.MAX_FILE_CHARS + InstructionExpander.TRUNCATION_MARKER.length(),
                result.length());
    }

    @Test
    void limitsMatchTheDocumentedDefaults() {
        assertEquals(5, InstructionExpander.MAX_DEPTH, "深度上限 5（顶层为第 1 层）");
        assertEquals(64_000, InstructionExpander.MAX_FILE_CHARS);
        assertEquals("…（内容过长，已截断）", InstructionExpander.TRUNCATION_MARKER);
    }

    @Test
    void ignoresInlineMentionsOfInclude() throws IOException {
        Path top = write("ACODE.md", "正文里提到 @include foo.md 但同行还有别的内容");
        assertEquals("正文里提到 @include foo.md 但同行还有别的内容", expandTop("ACODE.md"));
        assertTrue(Files.exists(top));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
