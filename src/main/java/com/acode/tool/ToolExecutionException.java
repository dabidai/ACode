package com.acode.tool;

/** 工具内部使用的异常包装：被 BaseTool 捕获并转为失败结果，不跨层抛出。 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
