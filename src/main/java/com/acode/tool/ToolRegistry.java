package com.acode.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册中心：集中注册、启用、禁用、查询工具。
 * 不感知执行细节——「未注册 / 已禁用 → 返回错误结果」由调用方（ToolExecutor）判断。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> disabled = new HashSet<>();

    /** 注册工具；同名重复注册直接拒绝 */
    public ToolRegistry register(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("工具已注册：" + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public void enable(String name) {
        disabled.remove(name);
    }

    public void disable(String name) {
        disabled.add(name);
    }

    /** 查询工具（含已禁用）；未注册返回 null */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 查询可用工具（已注册且未禁用）；未注册或已禁用返回 null */
    public Tool available(String name) {
        Tool tool = tools.get(name);
        return (tool != null && !disabled.contains(name)) ? tool : null;
    }

    public boolean isDisabled(String name) {
        return disabled.contains(name);
    }

    /** 按注册顺序返回全部工具（不可变视图） */
    public List<Tool> list() {
        return List.copyOf(tools.values());
    }

    /** 全部工具名（含已禁用） */
    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }
}
