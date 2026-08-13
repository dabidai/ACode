package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static BaseTool dummyTool(String name) {
        return new BaseTool(name, name + " 的描述", Permission.READ) {
            @Override
            protected List<ParamSpec> paramSpecs() {
                return List.of();
            }

            @Override
            protected ToolResult doExecute(JsonNode input, ToolContext context) {
                return ToolResult.success(name);
            }
        };
    }

    private static ToolRegistry registryWithSix() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep")) {
            registry.register(dummyTool(name));
        }
        return registry;
    }

    @Test
    void listReturnsAllRegisteredToolsWithDistinctNames() {
        ToolRegistry registry = registryWithSix();
        assertEquals(6, registry.list().size());
        Set<String> names = registry.list().stream().map(Tool::name).collect(Collectors.toSet());
        assertEquals(6, names.size(), "名称各不相同");
    }

    @Test
    void toAnthropicToolsFormatHasNameDescriptionInputSchema() {
        JsonNode array = ToolSchemaConverter.toAnthropicTools(registryWithSix().list());
        assertTrue(array.isArray());
        assertEquals(6, array.size());
        for (JsonNode tool : array) {
            assertTrue(tool.has("name"), "应含 name");
            assertTrue(tool.has("description"), "应含 description");
            assertTrue(tool.has("input_schema"), "应含 input_schema");
        }
    }

    @Test
    void disableThenEnableControlsAvailability() {
        ToolRegistry registry = registryWithSix();
        assertNotNull(registry.available("Bash"));
        registry.disable("Bash");
        assertNull(registry.available("Bash"), "禁用后不可用");
        assertNotNull(registry.get("Bash"), "禁用后仍能查到，用于区分未注册与已禁用");
        registry.enable("Bash");
        assertNotNull(registry.available("Bash"), "启用后恢复");
    }

    @Test
    void unknownToolReturnsNullWithoutThrowing() {
        ToolRegistry registry = registryWithSix();
        assertNull(registry.get("NoSuchTool"));
        assertNull(registry.available("NoSuchTool"));
    }

    @Test
    void duplicateRegistrationRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummyTool("A"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(dummyTool("A")));
    }
}
