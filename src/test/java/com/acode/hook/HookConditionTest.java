package com.acode.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HookConditionTest {
    @Test void exactRegexGlobCombinationsAndMissingFields() throws Exception {
        var c = new HookContext(HookEvents.PRE_TOOL_USE, "Bash", new ObjectMapper().readTree("{\"command\":\"echo rm  -rf /\",\"path\":\"src/a/Foo.java\",\"n\":123,\"obj\":{\"a\":1}}"), null, null);
        assertTrue(HookCondition.parse(Map.of("tool", "Bash", "args.n", 123)).matches(c));
        assertTrue(HookCondition.parse(Map.of("args.command", Map.of("regex", "rm\\s+-rf"))).matches(c));
        assertFalse(HookCondition.parse(Map.of("args.path", Map.of("glob", "*.java"))).matches(c));
        assertTrue(HookCondition.parse(Map.of("args.path", Map.of("glob", "src/**/*.j?va"))).matches(c));
        assertFalse(HookCondition.parse(Map.of("args.missing", Map.of("regex", ".*"))).matches(c));
        assertFalse(HookCondition.parse(Map.of("args.missing", "")).matches(c));
        assertTrue(HookCondition.parse(Map.of("all", List.of())).matches(c));
        assertFalse(HookCondition.parse(Map.of("any", List.of())).matches(c));
        assertTrue(HookCondition.parse(Map.of("all", List.of(Map.of("any", List.of(Map.of("tool", "Bash"), Map.of("tool", "WriteFile"))), Map.of("not", Map.of("args.path", Map.of("glob", "vendor/**")))))).matches(c));
        var windows = new HookContext(c.event(), c.tool(), new ObjectMapper().createObjectNode().put("path", "src\\a\\Foo.java"), null, null);
        assertTrue(HookCondition.parse(Map.of("args.path", Map.of("glob", "src/**/*.java"))).matches(windows));
        assertTrue(HookCondition.parse(Map.of("args.obj", Map.of("regex", "a"))).matches(c));
        assertFalse(HookCondition.parse(Map.of("args.obj", "{\"a\":1}")).matches(c));
        assertThrows(IllegalArgumentException.class, () -> HookCondition.parse(Map.of("not", List.of("x"))));
    }
    @Test void substitutionPreservesLiteralDollarsAndBackslashes() throws Exception {
        var c = new HookContext(HookEvents.POST_TOOL_USE, "WriteFile", new ObjectMapper().readTree("{\"file_path\":\"$x\\\\foo\",\"path\":\"ignored\",\"n\":123,\"obj\":[1]}"), "hello", "oops");
        assertEquals("post_tool_use/WriteFile/$x\\foo/hello/oops/123/[1]/", c.expand("$EVENT/$TOOL_NAME/$FILE_PATH/$MESSAGE/$ERROR/$TOOL_ARGS.n/$TOOL_ARGS.obj/$UNKNOWN"));
        assertEquals("", HookContext.lifecycle(HookEvents.SESSION_START, null).expand("$FILE_PATH$TOOL_ARGS.x"));
    }
}
