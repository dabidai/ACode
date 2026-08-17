package com.acode.tool;

/**
 * 工具执行结果。失败与超时统一带错误标记返回，不向调用方抛异常。
 * 成功时有正文输出（output），失败时有错误描述（errorMessage），二者互斥。
 * display 为仅供界面展示的正文（一行摘要 / diff），与回传模型的 content 解耦，默认 null。
 */
public final class ToolResult {

    private final boolean success;
    private final String output;
    private final String errorMessage;
    private final String display;

    private ToolResult(boolean success, String output, String errorMessage, String display) {
        this.success = success;
        this.output = output;
        this.errorMessage = errorMessage;
        this.display = display;
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output == null ? "" : output, null, null);
    }

    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage == null ? "" : errorMessage, null);
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

    /** 仅供界面展示的正文（一行摘要 / diff）；默认 null，不参与 content 与模型回传 */
    public String display() {
        return display;
    }

    /** 返回带展示正文的新副本；原对象不变，成功/失败均可携带 */
    public ToolResult withDisplay(String display) {
        return new ToolResult(success, output, errorMessage, display);
    }
}
