package com.acode.tool.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void prefersGitBashWhenPresent() throws Exception {
        Path fakeBash = tempDir.resolve("bash.exe");
        Files.createFile(fakeBash);
        ShellDetector detector = new ShellDetector(List.of(fakeBash.toString()));
        assertEquals("git-bash", detector.shellName());
        assertTrue(detector.commandPrefix().size() >= 2);
    }

    @Test
    void fallsBackToDefaultShellWhenNoGitBash() {
        ShellDetector detector = new ShellDetector(List.of("Z:\\no\\such\\bash.exe"));
        assertEquals("cmd", detector.shellName());
        assertFalse(detector.commandPrefix().isEmpty());
    }

    @Test
    void firstExistingCandidateWins() throws Exception {
        Path first = tempDir.resolve("first.exe");
        Path second = tempDir.resolve("second.exe");
        Files.createFile(first);
        Files.createFile(second);
        ShellDetector detector = new ShellDetector(List.of(first.toString(), second.toString()));
        assertEquals("git-bash", detector.shellName());
        assertEquals(first.toString(), detector.commandPrefix().get(0));
    }
}
