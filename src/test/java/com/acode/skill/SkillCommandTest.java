package com.acode;

import com.acode.config.AppConfig;
import com.acode.provider.*;
import com.acode.ui.OutputPane;
import com.acode.command.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

/** Real controller/dispatcher/runner wiring with no terminal or remote provider. */
class SkillCommandTest {
    @TempDir Path root;
    private String originalHome;
    @BeforeEach void isolateHome() throws Exception {
        originalHome = System.getProperty("user.home");
        // Keep logback's potentially open log outside @TempDir on Windows.
        System.setProperty("user.home", Files.createTempDirectory(Path.of("target").toAbsolutePath(), "skill-home-").toString());
    }
    @AfterEach void restoreHome() { System.setProperty("user.home", originalHome); }
    Path write(String name, String metadata, String body) throws Exception {
        Path file = root.resolve(".acode/skills/" + name + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: local skill\n" + metadata + "\n---\n" + body);
        return file;
    }
    @Test void slashReloadClearForkAndBuiltinConflicts() throws Exception {
        var config = new AppConfig(); config.setModel("default"); config.setProtocol("anthropic");
        config.setMemoryAuto(false); config.setMaxContextTokens(200000); config.setMaxIterations(5);
        var provider = FakeProvider.streaming("done");
        Path a = write("a", "model: selected", "BODY-A\n$ARGUMENTS");
        write("help", "", "help skill"); write("h", "", "alias skill");
        write("fork", "mode: fork", "fork body");
        write("broken", "allowedTools: not-a-list", "invalid");
        var controller = new ConversationController(provider, config, false);
        controller.setProjectRoot(root);
        var output = new OutputPane(); controller.setOutput(output); controller.setScreenWriter(new StringWriter());
        controller.initSkills();
        var processor = controller.commandProcessor();
        int start = output.lines().size(); processor.handleLine("/skill");
        var list = java.util.List.copyOf(output.lines().subList(start, output.lines().size()));
        start = output.lines().size(); processor.handleLine("/skill list");
        assertEquals(list, output.lines().subList(start, output.lines().size()));
        processor.handleLine("/skill info commit"); processor.handleLine("/help");
        assertEquals(0, provider.receivedRequests().size());
        assertTrue(String.join("\n", output.lines()).contains("[skill]"));
        assertTrue(String.join("\n", output.lines()).contains("classpath:skills/commit/SKILL.md"));
        processor.handleLine("/a extra");
        assertEquals(1, provider.receivedRequests().size());
        var request = provider.receivedRequests().getFirst(); assertEquals("selected", request.model());
        assertTrue(request.messages().stream().anyMatch(m -> m.content().contains("BODY-A\nextra")));
        assertFalse(request.messages().stream().anyMatch(m -> m.content().equals("/a extra")));
        processor.handleLine("/a extra"); assertEquals(1, provider.receivedRequests().size());
        Files.writeString(a, "broken"); processor.handleLine("/a extra"); assertEquals(1, provider.receivedRequests().size());
        write("a", "model: next", "BODY-NEW\n$ARGUMENTS"); processor.handleLine("/a extra");
        assertEquals("next", provider.receivedRequests().getLast().model());
        assertTrue(provider.receivedRequests().getLast().messages().stream().anyMatch(m -> m.content().contains("取代旧版本")));
        processor.handleLine("/fork"); assertEquals(2, provider.receivedRequests().size());
        assertTrue(String.join("\n", output.lines()).contains("阶段十二"));
        Files.delete(a); write("new-skill", "", "NEW BODY"); processor.handleLine("/skill reload");
        processor.handleLine("/new-skill");
        assertTrue(provider.receivedRequests().getLast().messages().getFirst().content().contains("new-skill: local skill"));
        assertFalse(provider.receivedRequests().getLast().messages().getFirst().content().contains("a: local skill"));
        processor.handleLine("/clear"); processor.handleLine("/new-skill");
        assertEquals("default", provider.receivedRequests().getLast().model());
        assertEquals(1, provider.receivedRequests().getLast().messages().stream().filter(m -> m.content().contains("NEW BODY")).count());
        int before = provider.receivedRequests().size(); processor.handleLine("/h");
        assertEquals(before, provider.receivedRequests().size());
        processor.handleLine("/review"); assertEquals(before + 1, provider.receivedRequests().size());
        assertEquals(CommandResult.EXIT, processor.handleLine("/quit"));
    }
}
