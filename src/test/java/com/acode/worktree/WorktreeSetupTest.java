package com.acode.worktree;

import com.acode.config.AppConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorktreeSetupTest {
    @TempDir Path root;
    GitRunner git=new GitRunner();
    @BeforeEach void init() throws Exception {
        git.run(root,"init","-b","main").require();
        git.run(root,"config","user.name","Test").require(); git.run(root,"config","user.email","test@example.invalid").require();
        git.run(root,"config","commit.gpgsign","false").require();
        Files.writeString(root.resolve(".gitignore"),".acode/\n.env\nsecret/\nnode_modules/\nsettings.local.json\n");
        git.run(root,"add",".").require(); git.run(root,"commit","-m","initial").require();
    }
    @Test void copiesOnlyDeclaredIgnoredFilesAndKeepsSharedHooksConfig() throws Exception {
        Files.writeString(root.resolve("settings.local.json"),"settings"); Files.writeString(root.resolve(".env"),"env");
        Files.createDirectory(root.resolve("secret")); Files.writeString(root.resolve("secret/keep"),"secret");
        Files.writeString(root.resolve(".worktreeinclude"),".env\n.acode/**\n");
        git.run(root,"config","core.hooksPath","custom-hooks").require();
        var c=new AppConfig(); c.setWorktreeSymlinkDirectories(List.of()); var m=new WorktreeManager(root,c);
        Path tree=Path.of(m.create("a").entry().path());
        assertEquals("env",Files.readString(tree.resolve(".env"))); assertEquals("settings",Files.readString(tree.resolve("settings.local.json")));
        assertFalse(Files.exists(tree.resolve("secret"))); assertFalse(Files.exists(tree.resolve(".acode")));
        assertEquals("custom-hooks",git.run(root,"config","core.hooksPath").require().strip());
        assertTrue(m.drainWarnings().stream().anyMatch(s->s.contains("worktreeConfig")));
    }
    @Test void existingFilesNotOverwrittenAndSetupContinues() throws Exception {
        Files.writeString(root.resolve("settings.local.json"),"tracked"); git.run(root,"add","-f","settings.local.json").require(); git.run(root,"commit","-m","track settings").require();
        Files.writeString(root.resolve("settings.local.json"),"local"); Files.writeString(root.resolve(".env"),"env");
        Files.writeString(root.resolve(".worktreeinclude"),".env");
        var c=new AppConfig(); c.setWorktreeSymlinkDirectories(List.of()); var m=new WorktreeManager(root,c);
        Path tree=Path.of(m.create("a").entry().path());
        assertEquals("tracked",Files.readString(tree.resolve("settings.local.json"))); assertEquals("env",Files.readString(tree.resolve(".env")));
        assertTrue(m.drainWarnings().stream().anyMatch(s->s.contains("本地配置")));
    }
    @Test void worktreeHooksConfigIsLocalWhenExtensionAlreadyEnabled() throws Exception {
        git.run(root,"config","extensions.worktreeConfig","true").require();
        git.run(root,"config","core.hooksPath","custom-hooks").require();
        var c=new AppConfig(); c.setWorktreeSymlinkDirectories(List.of()); Path tree=Path.of(new WorktreeManager(root,c).create("a").entry().path());
        assertEquals(root.resolve("custom-hooks").toString(),git.run(tree,"config","--worktree","core.hooksPath").require().strip());
        assertEquals("custom-hooks",git.run(root,"config","--local","core.hooksPath").require().strip());
    }
    @Test void linkOrJunctionIsUsableAndForceRemovalPreservesSource() throws Exception {
        Path source=Files.createDirectory(root.resolve("node_modules")); Files.writeString(source.resolve("keep"),"dependency");
        var c=new AppConfig(); c.setWorktreeSymlinkDirectories(List.of("node_modules")); var m=new WorktreeManager(root,c);
        Path tree=Path.of(m.create("a").entry().path());
        assertEquals("dependency",Files.readString(tree.resolve("node_modules/keep")));
        assertThrows(java.io.IOException.class,()->m.remove("a",false));
        m.remove("a",true); assertEquals("dependency",Files.readString(source.resolve("keep")));
    }
    @Test void badSetupIsBestEffort() throws Exception {
        Files.writeString(root.resolve(".worktreeinclude"),"!secret");
        var c=new AppConfig(); c.setWorktreeSymlinkDirectories(List.of("missing")); var m=new WorktreeManager(root,c);
        Path tree=Path.of(m.create("a").entry().path());
        assertTrue(Files.isDirectory(tree)); var warnings=m.drainWarnings();
        assertTrue(warnings.stream().anyMatch(x->x.contains("missing")));
        assertTrue(warnings.stream().anyMatch(x->x.contains("忽略文件")));
    }
    @Test void globSubset() {
        var m=new IncludeMatcher(List.of("#comment","", "*.log", "**/cache", "build/", "a?.txt"));
        assertTrue(m.matches("a.log")); assertFalse(m.matches("dir/a.log"));
        assertTrue(m.matches("cache")); assertTrue(m.matches("x/y/cache")); assertTrue(m.matches("build/a/b"));
        assertTrue(m.matches("ab.txt")); assertFalse(m.matches("abc.txt"));
        assertThrows(IllegalArgumentException.class,()->new IncludeMatcher(List.of("!secret")));
        assertThrows(IllegalArgumentException.class,()->new IncludeMatcher(List.of("../secret")));
    }
}
