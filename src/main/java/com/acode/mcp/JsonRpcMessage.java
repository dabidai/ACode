package com.acode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 消息类型：sealed 接口 + 四种消息形态。
 * 请求与通知都带 method，区别在是否有 id；响应分成功（result）与错误（error）。
 * id 统一归一为字符串（数字 id 由编解码层转字符串）。
 */
public sealed interface JsonRpcMessage
        permits JsonRpcMessage.Request, JsonRpcMessage.Response,
        JsonRpcMessage.Error, JsonRpcMessage.Notification {

    /** 请求：客户端→服务端，带 id，期待响应 */
    record Request(String method, JsonNode params, String id) implements JsonRpcMessage {
    }

    /** 成功响应：id 关联请求，result 为方法返回值 */
    record Response(String id, JsonNode result) implements JsonRpcMessage {
    }

    /** 错误响应：id 关联请求，code/message/data 描述失败原因 */
    record Error(String id, int code, String message, JsonNode data) implements JsonRpcMessage {
    }

    /** 通知：客户端→服务端，不带 id，服务端不响应 */
    record Notification(String method, JsonNode params) implements JsonRpcMessage {
    }
}
