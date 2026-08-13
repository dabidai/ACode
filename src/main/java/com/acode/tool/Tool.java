package com.acode.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一工具契约。每个工具暴露名称、描述、权限级别与参数定义，
 * 执行方法接收已解析的参数与执行上下文；错误以结果形态返回，不抛给上层。
 */
public interface Tool {

    /** 唯一名称，模型通过该名称调用工具 */
    String name();

    /** 面向模型的描述，说明工具用途与使用时机 */
    String description();

    /** 权限级别元信息（read/write/exec），本章仅标记不拦截 */
    Permission permission();

    /** 参数定义（JSON Schema），供转换为 Anthropic tools 参数格式 */
    JsonNode inputSchema();

    /** 执行工具。实现不应抛出异常，失败时返回带错误标记的结果 */
    ToolResult execute(JsonNode input, ToolContext context);
}
