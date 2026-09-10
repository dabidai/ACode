package com.acode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryScopeTest {

    @TempDir
    Path tempDir;

    private MemoryScope project;
    private MemoryScope user;

    @BeforeEach
    void setUp() {
        project = MemoryScope.project(tempDir.resolve("proj"));
        user = MemoryScope.user(tempDir.resolve("home"));
    }

    @Test
    void rootsFollowTheTwoAcodesNamespaces() {
        assertEquals(tempDir.resolve("proj").resolve(".acode").resolve("memory"), project.root());
        assertEquals(tempDir.resolve("home").resolve(".acode").resolve("memory"), user.root());
        assertEquals(project.root().resolve("MEMORY.md"), project.indexPath());
    }

    @Test
    void fourTypesSplitIntoProjectAndUserRoots() {
        assertEquals(MemoryScope.Kind.PROJECT, MemoryType.PROJECT.scopeKind());
        assertEquals(MemoryScope.Kind.PROJECT, MemoryType.REFERENCE.scopeKind());
        assertEquals(MemoryScope.Kind.USER, MemoryType.USER.scopeKind());
        assertEquals(MemoryScope.Kind.USER, MemoryType.FEEDBACK.scopeKind());

        MemoryStore store = new MemoryStore(project, user);
        assertEquals(project, store.scopeFor(MemoryType.PROJECT));
        assertEquals(project, store.scopeFor(MemoryType.REFERENCE));
        assertEquals(user, store.scopeFor(MemoryType.USER));
        assertEquals(user, store.scopeFor(MemoryType.FEEDBACK));
    }

    @Test
    void typeOrderPutsProjectLevelFirst() {
        assertEquals(java.util.List.of(MemoryType.PROJECT, MemoryType.REFERENCE,
                MemoryType.USER, MemoryType.FEEDBACK), MemoryType.inOrder());
    }

    @Test
    void acceptsWellFormedFileNames() {
        for (String name : new String[]{"project-deadline.md", "user-any.md", "feedback-testing.md",
                "reference-links.md", "project-a.md"}) {
            assertNotNull(project.resolve(name), name + " 应被接受");
        }
    }

    @Test
    void rejectsTraversalAbsolutePathsAndIllegalCharacters() {
        String[] rejected = {
                "../MEMORY.md",
                "..\\project-x.md",
                "/etc/passwd",
                "C:\\Windows\\system32\\x.md",
                "project-../escape.md",
                "project-.md",
                "project-UPPER.md",
                "other-thing.md",
                "project-x.txt",
                "project-" + "a".repeat(41) + ".md",
                "",
                null
        };
        for (String name : rejected) {
            assertNull(project.resolve(name), name + " 应被拒绝");
        }
    }

    @Test
    void writableCreatesDirectoryOnDemand() {
        assertFalse(Files.exists(project.root()));
        assertTrue(project.writable());
        assertTrue(Files.isDirectory(project.root()));
    }

    @Test
    void writableIsFalseWhenPathIsBlockedByAFile() throws IOException {
        Path root = tempDir.resolve("blocked");
        Files.createDirectories(root);
        Files.writeString(root.resolve(".acode"), "not a directory");
        assertFalse(MemoryScope.project(root).writable());
    }
}
