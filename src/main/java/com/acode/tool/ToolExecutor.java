package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用执行器：按工具名查注册中心，命中则带上下文执行。
 * 未注册 / 已禁用 / 执行异常都归为失败的 ToolResult，不向调用方抛异常。
 */
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolContext context;

    public ToolExecutor(ToolRegistry registry, ToolContext context) {
        this.registry = registry;
        this.context = context;
    }

    /** 执行一个工具调用，任何失败都归为 ToolResult 而非异常 */
    public ToolResult execute(String name, JsonNode input) {
        Tool tool = registry.available(name);
        if (tool == null) {
            boolean registered = registry.get(name) != null;
            String reason = registered ? "已被禁用" : "未注册";
            return ToolResult.failure("工具「" + name + "」" + reason);
        }
        return tool.execute(input, context);
    }
}
