package com.acode.subagent;

import com.acode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentDefinitionParserTest {
    final AgentDefinitionParser parser = new AgentDefinitionParser();
    final List<String> warnings = new ArrayList<>();
    AgentDefinition parse(String extra, String body) {
        return parser.parse("---\nname: reviewer\ndescription: review code\n" + extra + "\n---\n" + body, "test.md", "project", warnings::add);
    }
    @Test void fieldsAndBodyArePreserved() {
        String body = "\n  indented\n```java\ncode\n```\n";
        var d = parse("tools: [ReadFile, Bash]\ndisallowedTools: [Bash]\nmodel: haiku\nmaxTurns: 3\npermissionMode: acceptEdits", body);
        assertEquals("reviewer", d.agentType()); assertEquals("review code", d.whenToUse());
        assertEquals(List.of("ReadFile", "Bash"), d.tools()); assertEquals(List.of("Bash"), d.disallowedTools());
        assertEquals("haiku", d.model()); assertEquals(3, d.maxTurns()); assertEquals(PermissionMode.ACCEPT_EDITS, d.permissionMode());
        assertEquals(body, d.systemPrompt()); assertEquals("test.md", d.filePath()); assertEquals("project", d.source());
        assertTrue(warnings.isEmpty());
    }
    @Test void malformedRequiredUnknownAndUnsafeYamlAreRejected() {
        for (String header : List.of("description: x", "name: x", "name: x\ndescription: y\nallowedTools: []", "name: [", "name: a\nname: b\ndescription: x", "!!java.util.Date {}"))
            assertThrows(RuntimeException.class, () -> parser.parse("---\n" + header + "\n---\nbody", "bad", "user", warnings::add));
        assertThrows(IllegalArgumentException.class, () -> parse("tools: nope", ""));
        assertThrows(IllegalArgumentException.class, () -> parse("disallowedTools: [12]", ""));
    }
    @Test void fallbackAndEnums() {
        for (String value : List.of("-1", "abc", "1.5", "2147483648", "0")) assertEquals(20, parse("maxTurns: " + value, "").maxTurns());
        var d = parse("model: gpt4\npermissionMode: yolo", "");
        assertEquals("inherit", d.model()); assertEquals(PermissionMode.DEFAULT, d.permissionMode());
        assertEquals(7, warnings.size());
        for (String model : List.of("inherit", "sonnet", "opus", "haiku")) assertEquals(model, parse("model: " + model, "").model());
        for (PermissionMode mode : PermissionMode.values()) assertEquals(mode, parse("permissionMode: " + mode.configValue(), "").permissionMode());
    }
}
