package com.acode.worktree;

import com.acode.config.AppConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorktreeManagerTest {
    @TempDir Path temp;
    Path root;
    GitRunner git = new GitRunner();
    AppConfig config;
    WorktreeManager manager;
    @BeforeEach void init() throws Exception {
        root = Files.createDirectory(temp.resolve("repo with spaces"));
        git.run(root, "init", "-b", "main").require();
        git.run(root, "config", "user.name", "Test").require();
        git.run(root, "config", "user.email", "test@example.invalid").require();
        git.run(root, "config", "commit.gpgsign", "false").require();
        Files.writeString(root.resolve(".gitignore"), ".acode/\n.env\nnode_modules/\n");
        Files.writeString(root.resolve("file.txt"), "main");
        git.run(root, "add", ".").require(); git.run(root, "commit", "-m", "initial").require();
        config = new AppConfig(); config.setWorktreeSymlinkDirectories(List.of());
        manager = new WorktreeManager(root, config);
    }
    Path create(String name) throws Exception { return Path.of(manager.create(name).entry().path()); }
    void remote() throws Exception {
        Path remote = Files.createDirectory(temp.resolve("remote"));
        git.run(remote, "init", "--bare").require();
        git.run(root, "remote", "add", "origin", remote.toString()).require();
        git.run(root, "push", "origin", "main").require();
    }
    @Test void lifecycleIsolationPackedRefsAndSession() throws Exception {
        String cwd = System.getProperty("user.dir");
        Path a = create("team/alice"); Path b = create("bob");
        assertEquals("worktree-team+alice", git.run(a,"branch","--show-current").require().strip());
        manager.enter("team/alice"); assertEquals(a, manager.workingDirectory());
        assertTrue(manager.list().stream().anyMatch(WorktreeManager.View::current));
        Files.writeString(a.resolve("file.txt"), "isolated");
        assertEquals("main", Files.readString(root.resolve("file.txt")));
        manager.enter("bob"); assertEquals(b, manager.workingDirectory());
        git.run(root, "pack-refs", "--all").require();
        var reopened = new WorktreeManager(root, config);
        assertTrue(reopened.create("bob").recovered());
        assertTrue(reopened.restore().contains("bob"));
        assertEquals(b, reopened.workingDirectory());
        reopened.exit(); assertNull(new WorktreeManager(root,config).restore());
        manager.exit(); manager.remove("bob", false); assertFalse(Files.exists(b));
        assertEquals(cwd, System.getProperty("user.dir"));
    }
    @Test void protectionDirtyUntrackedIgnoredAndCommits() throws Exception {
        Path a = create("a");
        for (String file : List.of("file.txt", "new.txt", ".env")) {
            Files.writeString(a.resolve(file), "valuable");
            assertTrue(assertThrows(IOException.class, () -> manager.remove("a", false)).getMessage().contains("--force"));
            if (file.equals("file.txt")) git.run(a,"restore",file).require(); else Files.delete(a.resolve(file));
        }
        Files.writeString(a.resolve("file.txt"),"commit"); git.run(a,"add",".").require(); git.run(a,"commit","-m","work").require();
        assertThrows(IOException.class, () -> manager.remove("a",false));
        manager.remove("a",true); assertFalse(Files.exists(a));
        assertEquals("main", Files.readString(root.resolve("file.txt")));
    }
    @Test void currentAndLockedTreesCannotBeForcedAway() throws Exception {
        Path a = create("a"); manager.enter("a");
        assertThrows(IOException.class, () -> manager.remove("a",true)); manager.exit();
        git.run(root,"worktree","lock",a.toString()).require();
        assertThrows(IOException.class, () -> manager.remove("a",true)); assertTrue(Files.exists(a));
    }
    @Test void conflictingBranchesAndDirectoriesStayUntouched() throws Exception {
        git.run(root,"branch","worktree-a").require();
        String before = git.run(root,"rev-parse","worktree-a").require();
        assertThrows(IOException.class, () -> create("a"));
        assertEquals(before,git.run(root,"rev-parse","worktree-a").require());
        Path unknown=root.resolve(".acode/worktrees/unknown"); Files.createDirectories(unknown);
        Files.writeString(unknown.resolve("valuable"),"keep");
        assertThrows(IOException.class, () -> create("unknown"));
        assertThrows(IOException.class, () -> manager.remove("unknown",true));
        assertTrue(Files.exists(unknown.resolve("valuable")));
    }
    @Test void wrongBranchAndForgedSessionRejected() throws Exception {
        Path a=create("a"); git.run(a,"checkout","-b","different").require();
        assertThrows(IOException.class, () -> manager.remove("a",true));
        git.run(a,"checkout","worktree-a").require(); manager.enter("a");
        Path file=root.resolve(".acode/worktree_session.json");
        Files.writeString(file,Files.readString(file).replace("worktree-a","forged"));
        var reopened=new WorktreeManager(root,config);
        assertTrue(reopened.restore().contains("失效")); assertEquals(root,reopened.workingDirectory());
        Files.writeString(file,"broken JSON"); assertTrue(reopened.restore().contains("失效"));
    }
    @Test void detachedHeadAndUnknownBaselineRecovery() throws Exception {
        git.run(root,"checkout","--detach").require(); Path a=create("a");
        Files.delete(root.resolve(".acode/worktree_registry.json"));
        assertTrue(manager.create("a").recovered());
        assertThrows(IOException.class, () -> manager.remove("a",false));
        assertFalse(manager.cleanup("a")); assertTrue(Files.exists(a));
    }
    @Test void manualTemporaryLookingNamesNeverPruned() throws Exception {
        remote(); Path a=create("agent-a1234567");
        Files.setLastModifiedTime(a,FileTime.from(Instant.now().minus(10,java.time.temporal.ChronoUnit.DAYS)));
        assertEquals(0,manager.prune()); assertFalse(manager.cleanup("agent-a1234567")); assertTrue(Files.exists(a));
    }
    @Test void temporaryPruneRequiresAgeRemoteAndInactiveCleanState() throws Exception {
        String name="agent-a1234567";
        Path a=Path.of(manager.createTemporary(name).entry().path());
        FileTime old=FileTime.from(Instant.now().minus(10,java.time.temporal.ChronoUnit.DAYS));
        Files.setLastModifiedTime(a,old); assertEquals(0,manager.prune()); // No remote refs, keep.
        remote(); manager.enter(name); Files.setLastModifiedTime(a,old); assertEquals(0,manager.prune());
        assertEquals(0,new WorktreeManager(root,config).prune()); // persisted session protects another manager
        manager.exit(); Files.writeString(a.resolve(".env"),"keep"); Files.setLastModifiedTime(a,old);
        assertEquals(0,manager.prune()); Files.delete(a.resolve(".env"));
        Files.setLastModifiedTime(a,FileTime.from(Instant.now())); assertEquals(0,manager.prune());
        Files.setLastModifiedTime(a,old); assertEquals(1,manager.prune()); assertFalse(Files.exists(a));
    }
    @Test void namesRejectTraversalAndPortableGitInvalidNames() {
        assertEquals("team+alice",WorktreeNames.validate("team/alice"));
        assertEquals(64,WorktreeNames.validate("a".repeat(64)).length());
        for(String bad:List.of("", "../x","a//b","/a","a/", "CON", "aux.txt", "a..b", ".hidden", "x.lock", "x.","a b","a".repeat(65),"C:/x"))
            assertThrows(IllegalArgumentException.class,()->WorktreeNames.validate(bad),bad);
    }
    @Test void gitFailureCannotBecomeClean() throws Exception {
        Path a=Path.of(manager.createTemporary("agent-a1234567").entry().path());
        GitRunner broken=new GitRunner(){ @Override public Result run(Path dir,String... args) throws IOException { throw new IOException("failure"); }};
        assertFalse(new WorktreeManager(root,config,broken).cleanup("agent-a1234567")); assertTrue(Files.exists(a));
        assertThrows(IOException.class,()->new GitRunner(Duration.ofSeconds(5),1).run(root,"status"));
        assertThrows(IOException.class,()->new GitRunner(Duration.ofMillis(1),1024).execute(root,List.of("java","-version")));
    }
    @Test void substitutedGitFileAndMissingSessionAreRejected() throws Exception {
        Path a=create("a"); Path b=create("b");
        String original=Files.readString(a.resolve(".git"));
        Files.writeString(a.resolve(".git"),Files.readString(b.resolve(".git")));
        assertThrows(IOException.class,()->manager.remove("a",true));
        assertTrue(Files.exists(a.resolve("file.txt"))); assertTrue(Files.exists(b.resolve("file.txt")));
        Files.writeString(a.resolve(".git"),original); manager.enter("a");
        git.run(root,"worktree","remove",a.toString()).require();
        var restored=new WorktreeManager(root,config);
        assertTrue(restored.restore().contains("失效")); assertEquals(root,restored.workingDirectory());
    }
    @Test void metadataCorruptionFailsClosed() throws Exception {
        Path a=create("a"); Files.writeString(root.resolve(".acode/worktree_registry.json"),"broken");
        assertThrows(IOException.class,()->manager.remove("a",true)); assertTrue(Files.exists(a));
    }
    @Test void redirectedManagerDirectoryRejected() throws Exception {
        Path outside=Files.createDirectory(temp.resolve("outside"));
        try { Files.createSymbolicLink(root.resolve(".acode"),outside); }
        catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw e;
            git.execute(root,List.of("cmd.exe","/d","/c","mklink","/J",root.resolve(".acode").toString(),outside.toString())).require();
        }
        try {
            assertThrows(IOException.class,()->manager.create("a"));
            try(var files=Files.list(outside)) { assertEquals(0,files.count()); }
        } finally { Files.delete(root.resolve(".acode")); }
    }
}
