package com.acode.subagent;

import com.acode.tool.*;
import com.acode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.acode.subagent.SubAgentTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolFilterTest {
    @Test void ordinaryChildrenCannotInheritLeadBoundTeamTools() {
        var registry = new ToolRegistry();
        for (String name : List.of("TeamCreate", "TeamDelete", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage"))
            registry.register(tool(name, Permission.WRITE, new AtomicInteger()));
        assertTrue(ToolFilter.filter(registry, null, true).isEmpty());
        assertTrue(ToolFilter.filter(registry, definition(List.of(), List.of(), 20, PermissionMode.DEFAULT), false).isEmpty());
    }
    @Test void globalForkIntersectionAndBlacklist() {
        var registry = new ToolRegistry();
        for (String name : List.of("Agent", "AskUser", "ExitPlanMode", "ReadFile", "WriteFile", "Bash")) registry.register(tool(name, Permission.READ, new AtomicInteger()));
        var plain = definition(List.of(), List.of(), 20, PermissionMode.DEFAULT);
        assertEquals(List.of("ReadFile", "WriteFile", "Bash"), ToolFilter.filter(registry, plain, false).stream().map(Tool::name).toList());
        assertEquals(List.of("Agent", "ReadFile", "WriteFile", "Bash"), ToolFilter.filter(registry, null, true).stream().map(Tool::name).toList());
        assertEquals(List.of("ReadFile", "WriteFile"), ToolFilter.filter(registry, definition(List.of("ReadFile", "WriteFile", "Bash"), List.of("Bash"), 20, PermissionMode.DEFAULT), false).stream().map(Tool::name).toList());
        assertThrows(IllegalArgumentException.class, () -> ToolFilter.filter(registry, definition(List.of("Missing"), List.of(), 20, PermissionMode.DEFAULT), false));
        assertEquals(6, registry.list().size());
        registry.disable("Bash"); assertFalse(ToolFilter.filter(registry, plain, false).stream().anyMatch(t -> t.name().equals("Bash")));
    }
}
