package com.acode.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DefaultToolset 组装契约：内置六工具按序注册、权限分类正确。
 * 权限分类是权限检查矩阵（PermissionChecker/模式）的输入，注册错类会直接改变放行/确认行为。
 */
class DefaultToolsetTest {

    @Test
    void registerAllRegistersSixToolsWithExpectedPermissions() {
        ToolRegistry registry = new ToolRegistry();
        DefaultToolset.registerAll(registry);

        Map<String, Permission> expected = new LinkedHashMap<>();
        expected.put("ReadFile", Permission.READ);
        expected.put("WriteFile", Permission.WRITE);
        expected.put("EditFile", Permission.WRITE);
        expected.put("Glob", Permission.READ);
        expected.put("Grep", Permission.READ);
        expected.put("Bash", Permission.EXEC);

        assertEquals(expected.size(), registry.list().size(), "应恰好注册 6 个内置工具");
        assertEquals(expected.keySet().stream().toList(), registry.names(),
                "注册顺序应稳定（ReadFile 起、Bash 止）");
        for (Map.Entry<String, Permission> entry : expected.entrySet()) {
            Tool tool = registry.get(entry.getKey());
            assertEquals(entry.getValue(), tool.permission(),
                    entry.getKey() + " 权限分类应为 " + entry.getValue());
        }
        assertNull(registry.get("AskUser"), "AskUser 属 UI 层单独注册，不应在 DefaultToolset 内");
        assertNull(registry.get("ExitPlanMode"), "ExitPlanMode 由 Agent 构造时注册，不应在 DefaultToolset 内");
    }
}
