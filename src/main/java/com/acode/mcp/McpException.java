package com.acode.mcp;

/**
 * MCP 协议层运行时异常。静态工厂区分失败类别，供上层按类别处理
 * （连接失败 / 协议错误 / 超时 / 远端工具失败）。
 */
public class McpException extends RuntimeException {

    public enum Kind { CONNECTION, PROTOCOL, TIMEOUT, REMOTE }

    private final Kind kind;

    private McpException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 传输层连接失败：进程启动失败 / 网络不通 / 非 2xx 等 */
    public static McpException connectionFailed(String detail) {
        return new McpException(Kind.CONNECTION, "MCP 连接失败：" + detail);
    }

    /** 协议错误：非法消息 / 缺字段 / 远端返回格式不符 */
    public static McpException protocolError(String detail) {
        return new McpException(Kind.PROTOCOL, "MCP 协议错误：" + detail);
    }

    /** 请求超时 */
    public static McpException timeout(String detail) {
        return new McpException(Kind.TIMEOUT, "MCP 请求超时：" + detail);
    }

    /** 远端工具执行失败（tools/call 返回 isError） */
    public static McpException remoteError(String detail) {
        return new McpException(Kind.REMOTE, "MCP 远端错误：" + detail);
    }
}
