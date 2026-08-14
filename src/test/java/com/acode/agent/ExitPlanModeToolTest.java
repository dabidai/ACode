package com.acode.agent;

import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPlanModeToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExitPlanModeTool tool = new ExitPlanModeTool();

    @TempDir
    Path tempDir;

    @Test
    void planModeReturnsSuccess() {
        ToolContext context = new ToolContext(tempDir, true);
        ToolResult result = tool.execute(JSON.createObjectNode(), context);

        assertFalse(result.isError());
        assertTrue(result.content().contains("计划将在本轮结束后交付"));
    }

    @Test
    void nonPlanModeReturnsError() {
        ToolContext context = new ToolContext(tempDir, false);
        ToolResult result = tool.execute(JSON.createObjectNode(), context);

        assertTrue(result.isError());
        assertTrue(result.content().contains("只能在 plan 模式下调用"));
    }

    @Test
    void defaultsToNonPlanMode() {
        ToolContext context = new ToolContext(tempDir);
        ToolResult result = tool.execute(JSON.createObjectNode(), context);

        assertTrue(result.isError());
    }
}
