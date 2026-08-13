package com.acode.tool;

/**
 * 工具执行结果。失败与超时统一带错误标记返回，不向调用方抛异常。
 * 成功时有正文输出（output），失败时有错误描述（errorMessage），二者互斥。
 */
public final class ToolResult {

    private final boolean success;
    private final String output;
    private final String errorMessage;

    private ToolResult(boolean success, String output, String errorMessage) {
        this.success = success;
        this.output = output;
        this.errorMessage = errorMessage;
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output == null ? "" : output, null);
    }

    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage == null ? "" : errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isError() {
        return !success;
    }

    /** 成功时的正文输出；失败时为空串 */
    public String output() {
        return output;
    }

    /** 失败时的错误描述；成功时为 null */
    public String errorMessage() {
        return errorMessage;
    }

    /** 供回传模型 / 界面展示的正文：成功取 output，失败取 errorMessage */
    public String content() {
        return success ? output : errorMessage;
    }
}
