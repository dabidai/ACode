package com.acode.tool;

import com.acode.provider.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static BaseTool dummyTool(String name) {
        return new BaseTool(name, name + " 的描述", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() {
                return List.of();
            }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                return ToolResult.success(name + "-done");
            }
        };
    }

    @Test
    void executesRegisteredToolAndReturnsOutput() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("stub"));
        ToolExecutor executor = new ToolExecutor(registry, new ToolContext(tempDir));
        ToolResult result = executor.execute(new ToolUseBlock("id-1", "stub", JSON.createObjectNode()));
        assertTrue(result.isSuccess());
        assertEquals("stub-done", result.output());
    }

    @Test
    void unknownToolReturnsFailureWithoutThrowing() {
        ToolExecutor executor = new ToolExecutor(new ToolRegistry(), new ToolContext(tempDir));
        ToolResult result = executor.execute(new ToolUseBlock("id-1", "NoSuchTool", JSON.createObjectNode()));
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("未注册"), "错误应指明未注册：" + result.errorMessage());
    }

    @Test
    void disabledToolReturnsFailure() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("stub"));
        registry.disable("stub");
        ToolExecutor executor = new ToolExecutor(registry, new ToolContext(tempDir));
        ToolResult result = executor.execute(new ToolUseBlock("id-1", "stub", JSON.createObjectNode()));
        assertTrue(result.isError());
        assertTrue(result.errorMessage().contains("禁用"), "错误应指明已禁用：" + result.errorMessage());
    }
}
