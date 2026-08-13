package com.acode.tool;

/**
 * 工具参数的简单声明：名称、JSON 类型、是否必填、面向模型的描述。
 * BaseTool 据此生成默认 inputSchema 并做参数校验。
 */
public record ParamSpec(String name, Type type, boolean required, String description) {

    public enum Type { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT }

    public static ParamSpec required(String name, Type type, String description) {
        return new ParamSpec(name, type, true, description);
    }

    public static ParamSpec optional(String name, Type type, String description) {
        return new ParamSpec(name, type, false, description);
    }
}
