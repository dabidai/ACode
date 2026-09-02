package com.acode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 编解码。单入口 {@link #parse} 按消息形态判别类型：
 * 有 method 无 id → 通知；有 method 有 id → 请求；无 method 有 id → 响应（error 存在则解析错误）。
 * 非法 JSON / 缺 jsonrpc="2.0" / 既无 method 也无 id → 协议错误。
 * id 一律 {@code asText()} 归一为字符串（数字 id 转字符串），请求-响应按字符串 id 关联。
 */
public final class JsonRpcCodec {

    public static final String VERSION = "2.0";

    /** JSON-RPC 标准错误码 */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonRpcCodec() {
    }

    /** 反序列化单入口：按 method / id / error 字段判别消息类型 */
    public static JsonRpcMessage parse(String text) {
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw McpException.protocolError("非法 JSON：" + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw McpException.protocolError("消息必须是 JSON 对象");
        }
        if (!VERSION.equals(node.path("jsonrpc").asText())) {
            throw McpException.protocolError("缺少 jsonrpc=2.0");
        }
        String method = node.has("method") ? node.get("method").asText() : null;
        boolean hasId = node.has("id") && !node.get("id").isNull();
        if (method != null && !hasId) {
            return new JsonRpcMessage.Notification(method, node.get("params"));
        }
        if (method != null) {
            return new JsonRpcMessage.Request(method, node.get("params"), node.get("id").asText());
        }
        if (hasId) {
            JsonNode error = node.get("error");
            if (error != null && error.isObject()) {
                return new JsonRpcMessage.Error(node.get("id").asText(),
                        error.path("code").asInt(),
                        error.path("message").asText(""),
                        error.get("data"));
            }
            return new JsonRpcMessage.Response(node.get("id").asText(), node.get("result"));
        }
        throw McpException.protocolError("消息既无 method 也无 id");
    }

    /** 按消息类型序列化（传输层通用入口） */
    public static String serialize(JsonRpcMessage message) {
        return switch (message) {
            case JsonRpcMessage.Request m -> serializeRequest(m.method(), m.params(), m.id());
            case JsonRpcMessage.Notification m -> serializeNotification(m.method(), m.params());
            case JsonRpcMessage.Response m -> serializeResponse(m.id(), m.result());
            case JsonRpcMessage.Error m -> serializeError(m.id(), m.code(), m.message(), m.data());
        };
    }

    public static String serializeRequest(String method, JsonNode params, String id) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("method", method);
        putIfNotNull(node, "params", params);
        node.put("id", id);
        return toJson(node);
    }

    public static String serializeNotification(String method, JsonNode params) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("method", method);
        putIfNotNull(node, "params", params);
        return toJson(node);
    }

    public static String serializeResponse(String id, JsonNode result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("id", id);
        putIfNotNull(node, "result", result);
        return toJson(node);
    }

    public static String serializeError(String id, int code, String message, JsonNode data) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", VERSION);
        node.put("id", id);
        ObjectNode error = node.putObject("error");
        error.put("code", code);
        error.put("message", message);
        putIfNotNull(error, "data", data);
        return toJson(node);
    }

    private static void putIfNotNull(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private static String toJson(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw McpException.protocolError("消息序列化失败：" + e.getOriginalMessage());
        }
    }
}
