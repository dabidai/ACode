package com.acode.prompt;

import com.acode.prompt.EnvironmentDetector.EnvironmentSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentDetectorTest {

    @TempDir
    Path tempDir;

    private static void gitInit(Path dir) throws Exception {
        Process p = new ProcessBuilder("git", "init").directory(dir.toFile())
                .redirectErrorStream(true).start();
        p.waitFor();
    }

    @Test
    void detectsGitRepoWithBranch() throws Exception {
        gitInit(tempDir);
        EnvironmentSnapshot env = EnvironmentDetector.detect("m", tempDir.toString());
        assertTrue(env.isGitRepo(), "git-init directory must be a repo");
        assertNotNull(env.gitBranch());
        assertFalse(env.gitBranch().isEmpty(), "fresh repo should have a branch");
    }

    @Test
    void nonRepoDirectorySucceedsQuietly() {
        EnvironmentSnapshot env = EnvironmentDetector.detect("m", tempDir.toString());
        assertFalse(env.isGitRepo());
        assertEquals("", env.gitBranch());
    }

    @Test
    void shellFallsBackToBashWhenEnvUnset() {
        assertEquals("bash", EnvironmentDetector.resolveShell(null));
        assertEquals("bash", EnvironmentDetector.resolveShell(""));
        assertEquals("/bin/zsh", EnvironmentDetector.resolveShell("/bin/zsh"));
    }

    @Test
    void renderContainsAllFieldLines() {
        EnvironmentSnapshot env = new EnvironmentSnapshot("/work", "windows 11", "amd64",
                "bash", true, "main", "claude-opus-4-7", "2026-08-18");
        String text = EnvironmentDetector.render(env);
        assertTrue(text.startsWith("# Environment"));
        assertTrue(text.contains(" - Working directory: /work"));
        assertTrue(text.contains(" - Platform: windows 11/amd64"));
        assertTrue(text.contains(" - Shell: bash"));
        assertTrue(text.contains(" - Is git repo: true"));
        assertTrue(text.contains(" - Git branch: main"));
        assertTrue(text.contains(" - Model: claude-opus-4-7"));
        assertTrue(text.contains(" - Date: 2026-08-18"));
    }

    @Test
    void renderOmitsBranchAndModelWhenUnset() {
        EnvironmentSnapshot env = new EnvironmentSnapshot("/work", "windows 11", "amd64",
                "bash", false, "", "", "2026-08-18");
        String text = EnvironmentDetector.render(env);
        assertTrue(text.contains(" - Is git repo: false"));
        assertFalse(text.contains("Git branch"));
        assertFalse(text.contains("Model:"));
        assertTrue(text.contains(" - Date: 2026-08-18"));
    }
}
