package com.acode;

import com.acode.config.AppConfig;
import com.acode.permission.PermissionResponse;
import com.acode.provider.*;
import com.acode.ui.OutputPane;
import com.acode.worktree.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import static com.acode.provider.FakeProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class WorktreeEndToEndTest {
    @TempDir Path root;
    @TempDir Path nonRepository;
    String home;
    AppConfig config;
    GitRunner git=new GitRunner();
    @BeforeEach void init() throws Exception {
        home=System.getProperty("user.home");
        System.setProperty("user.home",Files.createTempDirectory(Path.of("target").toAbsolutePath(), "ch13-home-").toString());
        git.run(root,"init","-b","main").require();
        git.run(root,"config","user.name","Test").require(); git.run(root,"config","user.email","test@example.invalid").require();
        git.run(root,"config","commit.gpgsign","false").require();
        Files.writeString(root.resolve(".gitignore"),".acode/\nhome/\n"); Files.writeString(root.resolve("file.txt"),"MAIN");
        git.run(root,"add",".").require(); git.run(root,"commit","-m","initial").require();
        config=new AppConfig(); config.setProtocol("anthropic"); config.setModel("test"); config.setMemoryAuto(false);
        config.setMaxContextTokens(20000); config.setMaxIterations(5); config.setWorktreeSymlinkDirectories(List.of());
    }
    @AfterEach void restore() { System.setProperty("user.home",home); }
    ConversationController controller(ChatProvider p,OutputPane out,boolean resume) {
        var c=new ConversationController(p,config,resume); c.setProjectRoot(root); c.setOutput(out); c.setScreenWriter(new StringWriter());
        c.setConfirmAnswerer(event->PermissionResponse.ALLOW); return c;
    }
    @Test void cachedRunnerUsesCurrentToolDirectoryAndCommandsNeverCallProvider() throws Exception {
        var j=JsonNodeFactory.instance;
        var provider=scripted(List.of(
                List.of(delta("warmup"),complete()),
                List.of(toolUse("write","WriteFile",j.objectNode().put("file_path","file.txt").put("content","ISOLATED")),complete()),
                List.of(toolUse("read","ReadFile",j.objectNode().put("file_path","file.txt")),complete()),
                List.of(toolUse("pwd","Bash",j.objectNode().put("command","git rev-parse --show-toplevel")),complete()),
                List.of(delta("done"),complete()),
                List.of(toolUse("read-main","ReadFile",j.objectNode().put("file_path","file.txt")),complete()),
                List.of(delta("back"),complete())));
        var out=new OutputPane(); var c=controller(provider,out,false);
        try {
            c.handleExchange("warmup",()->false,()->{});
            c.commandProcessor().handleLine("/worktree CREATE team/alice");
            Path tree=root.resolve(".acode/worktrees/team+alice");
            assertEquals(tree,c.worktrees().workingDirectory()); assertEquals(1,provider.receivedRequests().size());
            c.handleExchange("write read pwd",()->false,()->{});
            assertTrue(provider.receivedRequests().get(1).messages().stream().anyMatch(m -> m.content().contains("Working directory: " + tree)));
            assertEquals("ISOLATED",Files.readString(tree.resolve("file.txt"))); assertEquals("MAIN",Files.readString(root.resolve("file.txt")));
            String results=provider.receivedRequests().get(4).messages().stream().flatMap(m->m.blocks().stream())
                    .filter(b->b instanceof ToolResultBlock).map(b->((ToolResultBlock)b).content()).reduce("",(a,b)->a+"\n"+b);
            assertTrue(results.contains("ISOLATED")); assertTrue(results.replace('\\','/').contains(tree.toString().replace('\\','/')),results);
            c.commandProcessor().handleLine("/worktree"); c.commandProcessor().handleLine("/status");
            c.commandProcessor().handleLine("/worktree exit");
            c.handleExchange("read main",()->false,()->{});
            assertTrue(provider.receivedRequests().getLast().messages().stream().flatMap(m->m.blocks().stream())
                    .anyMatch(b->b instanceof ToolResultBlock r && r.toolUseId().equals("read-main") && r.content().contains("MAIN")));
            c.commandProcessor().handleLine("/worktree remove team/alice"); assertTrue(Files.exists(tree));
            c.commandProcessor().handleLine("/worktree remove team/alice --force"); assertFalse(Files.exists(tree));
            assertEquals(7,provider.receivedRequests().size());
        } finally { c.closeSession(); }
    }
    @Test void startupResumeRestoresEvenWithoutConversationAndExitClears() throws Exception {
        var p=scripted(List.of()); var out=new OutputPane(); var first=controller(p,out,false);
        try { first.commandProcessor().handleLine("/worktree create resume-me"); } finally { first.closeSession(); }
        var next=controller(p,out,true);
        try {
            next.restoreIfResume(); assertEquals(root.resolve(".acode/worktrees/resume-me"),next.worktrees().workingDirectory());
            next.startWorktreeCleanup(); next.startWorktreeCleanup();
            next.commandProcessor().handleLine("/worktree exit");
        } finally { next.closeSession(); }
        var last=controller(p,out,true);
        try { last.restoreIfResume(); assertEquals(root,last.worktrees().workingDirectory()); assertTrue(p.receivedRequests().isEmpty()); }
        finally { last.closeSession(); }
    }
    @Test void commandSyntaxHelpAndNonRepository() throws Exception {
        var p=scripted(List.of()); var out=new OutputPane(); var c=controller(p,out,false);
        try {
            for(String line:List.of("/worktree", "/help", "/worktree create", "/worktree exit extra", "/worktree prune extra", "/worktree nonsense", "/worktree remove a --unknown"))
                c.commandProcessor().handleLine(line);
            String text=String.join("\n",out.lines()); assertTrue(text.contains("没有 Worktree")); assertTrue(text.contains("/worktree")); assertTrue(text.contains("用法："));
            assertTrue(p.receivedRequests().isEmpty());
            // Use an independent non-repository outside this Git root.
            Path other=nonRepository;
            assertThrows(java.io.IOException.class,()->new WorktreeManager(other,config).list());
            c.setProjectRoot(other); c.commandProcessor().handleLine("/worktree");
            assertTrue(String.join("\n",out.lines()).contains("当前目录不是 Git 仓库，无法使用 Worktree"));
        } finally { c.closeSession(); }
    }
}
