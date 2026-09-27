package com.acode.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {
    @TempDir Path project;
    @TempDir Path home;
    void write(Path root, String file, String text) throws Exception {
        var path = root.resolve(".acode/agents/" + file); Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    @Test void priorityWarningsAndSwitch() throws Exception {
        var builtins = new AgentRegistry(project, home, true);
        assertEquals(4, builtins.names().size()); assertEquals("builtin", builtins.get("Explore").source());
        assertEquals("inherit", builtins.get("Explore").model());
        write(home, "explore.md", "---\nname: Explore\ndescription: user\n---\nuser");
        assertEquals("user", new AgentRegistry(project, home, true).get("Explore").source());
        write(project, "arbitrary.md", "---\nname: Explore\ndescription: project\n---\nproject");
        write(project, "bad.md", "---\ndescription: broken\n---\n");
        write(project, "invalid.md", "---\nname: [\n---\n");
        var result = new AgentRegistry(project, home, false);
        assertEquals("project", result.get("Explore").source()); assertNull(result.get("Verification"));
        assertEquals(2, result.warnings().size()); assertTrue(result.warnings().stream().anyMatch(s -> s.contains("bad.md") && s.contains("name")));
        assertNotNull(result.get("general-purpose"));
        write(project, "arbitrary.md", "broken"); assertEquals("project", result.get("Explore").source(), "loaded only once");
    }
    @Test void boilerplateMatchesTeachingText() throws Exception {
        String prompt = Files.readString(Path.of("docs/ch12/prompt.md"));
        assertTrue(prompt.replace("\r\n", "\n").contains(ForkBoilerplate.TEXT));
    }
    @Test void malformedOverrideRetainsValidLowerPriorityDefinition() throws Exception {
        write(home, "valid.md", "---\nname: Explore\ndescription: user fallback\n---\nUSER BODY");
        write(project, "override.md", "---\nname: Explore\ndescription: broken override\nallowedTools: [ReadFile]\n---\nBROKEN BODY");
        var registry = new AgentRegistry(project, home, true);
        assertEquals("user", registry.get("Explore").source()); assertEquals("USER BODY", registry.get("Explore").systemPrompt());
        assertEquals(1, registry.warnings().size()); assertTrue(registry.warnings().getFirst().contains("allowedTools"));
        assertEquals(4, registry.names().size());
    }
}
