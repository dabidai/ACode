package com.acode.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class WorktreeConfigTest {
    @TempDir Path root;
    @Test void defaultsAndFieldLevelMerge() throws Exception {
        Path global=root.resolve("global.yaml"); var c=ConfigLoader.load(global,root);
        assertEquals(7,c.getWorktreeStaleAfterDays()); assertEquals(List.of("node_modules",".venv","vendor"),c.getWorktreeSymlinkDirectories());
        Files.writeString(global,"worktree:\n  staleAfterDays: 12\n  symlinkDirectories: [deps]\n");
        Files.createDirectories(root.resolve(".acode")); Files.writeString(root.resolve(".acode/config.yaml"),"worktree:\n  symlinkDirectories: []\n");
        c=ConfigLoader.load(global,root); assertEquals(12,c.getWorktreeStaleAfterDays()); assertTrue(c.getWorktreeSymlinkDirectories().isEmpty());
    }
    @Test void rejectsBadTypesNamesAndUnknownKeys() throws Exception {
        Path global=root.resolve("global.yaml");
        for(String value:List.of("worktree: []", "worktree: null", "worktree: {unknown: 1}", "unknown: 1",
                "worktree: {staleAfterDays: 0}","worktree: {staleAfterDays: -1}","worktree: {staleAfterDays: 1.2}",
                "worktree: {symlinkDirectories: null}","worktree: {symlinkDirectories: [../x]}","worktree: {symlinkDirectories: [a/b]}",
                "worktree: {symlinkDirectories: [.git]}","worktree: {symlinkDirectories: [CON]}","worktree: {symlinkDirectories: ['C:/x']}")) {
            Files.writeString(global,value); assertThrows(ConfigException.class,()->ConfigLoader.load(global,root),value);
        }
    }
}
