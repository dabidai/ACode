package com.acode.team;

import com.acode.permission.*;
import com.acode.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TeamPermissionTest {
    @TempDir Path root;
    @Test void allocatedRootAndParentCeilingApplyToWorkingDirectoryOverload() throws Exception {
        Path childRoot = Files.createDirectories(root.resolve("child"));
        Files.createDirectories(childRoot.resolve(".acode/plans"));
        var parent = new PermissionChecker(PermissionMode.DEFAULT, root,
                new RuleEngine(root.resolve("u"), root.resolve("p"), root.resolve("l")));
        var child = parent.child(PermissionMode.PLAN, childRoot);
        Tool write = new BaseTool("WriteFile", "test", Permission.WRITE) {
            protected List<ParamSpec> paramSpecs() { return List.of(); }
            protected ToolResult doExecute(JsonNode input, ToolContext context) { return ToolResult.success(""); }
        };
        var json = new ObjectMapper();
        assertEquals(PermissionMode.Decision.ASK, child.check(write,
                json.createObjectNode().put("file_path", ".acode/plans/test.md"), childRoot).decision());
        assertEquals(PermissionMode.Decision.DENY, child.check(write,
                json.createObjectNode().put("file_path", root.toAbsolutePath().getRoot().resolve("acode-outside-test.md").toString()), childRoot).decision());
        assertEquals(PermissionMode.Decision.DENY, child.readOnly().check(write,
                json.createObjectNode().put("file_path", ".acode/plans/test.md"), childRoot).decision());
    }
}
