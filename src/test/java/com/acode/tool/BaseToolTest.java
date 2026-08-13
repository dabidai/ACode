package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static com.acode.tool.ParamSpec.Type.INTEGER;
import static com.acode.tool.ParamSpec.Type.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolContext CONTEXT = new ToolContext(Path.of("."));

    /** 测试用工具：必填 file_path + 可选 limit，执行时回显 file_path */
    private static BaseTool echoTool() {
        return new BaseTool("echo", "回显参数", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() {
                return List.of(
                        ParamSpec.required("file_path", STRING, "目标文件"),
                        ParamSpec.optional("limit", INTEGER, "读取行数上限"));
            }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                return ToolResult.success(input.get("file_path").asText());
            }
        };
    }

    @Test
    void successExecutionReturnsOutput() {
        ToolResult r = echoTool().execute(JSON.createObjectNode().put("file_path", "a.txt"), CONTEXT);
        assertTrue(r.isSuccess());
        assertFalse(r.isError());
        assertEquals("a.txt", r.output());
        assertEquals("a.txt", r.content());
    }

    @Test
    void missingRequiredParamReturnsFailureWithParamName() {
        ToolResult r = echoTool().execute(JSON.createObjectNode(), CONTEXT);
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("file_path"), "错误文本应含参数名");
    }

    @Test
    void wrongTypeReturnsFailureWithParamName() {
        ToolResult r = echoTool().execute(
                JSON.createObjectNode().put("file_path", "a.txt").put("limit", "3"), CONTEXT);
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("limit"), "错误文本应含参数名");
    }

    @Test
    void optionalParamCanBeOmitted() {
        ToolResult r = echoTool().execute(JSON.createObjectNode().put("file_path", "a.txt"), CONTEXT);
        assertTrue(r.isSuccess());
    }

    @Test
    void internalRuntimeExceptionBecomesFailureResult() {
        BaseTool throwing = new BaseTool("boom", "总是抛异常", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() {
                return List.of();
            }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                throw new IllegalStateException("boom");
            }
        };
        ToolResult r = throwing.execute(JSON.createObjectNode(), CONTEXT);
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("boom"), "错误文本应含异常信息");
    }

    @Test
    void toolExecutionExceptionBecomesFailureResult() {
        BaseTool throwing = new BaseTool("boom", "抛包装异常", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() {
                return List.of();
            }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                throw new ToolExecutionException("内部失败");
            }
        };
        ToolResult r = throwing.execute(JSON.createObjectNode(), CONTEXT);
        assertTrue(r.isError());
        assertTrue(r.errorMessage().contains("内部失败"));
    }

    @Test
    void inputSchemaContainsPropertiesAndRequired() {
        JsonNode schema = echoTool().inputSchema();
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.path("properties").has("file_path"));
        assertTrue(schema.path("properties").has("limit"));
        assertEquals("file_path", schema.path("required").get(0).asText());
    }
}
