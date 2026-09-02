package com.acode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesRequestWithIdAndNormalizesNumericId() {
        JsonRpcMessage message = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"},\"id\":1}");
        assertInstanceOf(JsonRpcMessage.Request.class, message);
        JsonRpcMessage.Request request = (JsonRpcMessage.Request) message;
        assertEquals("tools/call", request.method());
        assertEquals("echo", request.params().path("name").asText());
        assertEquals("1", request.id(), "数字 id 应归一为字符串");
    }

    @Test
    void parsesNotificationWithoutId() {
        JsonRpcMessage message = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertInstanceOf(JsonRpcMessage.Notification.class, message);
        assertEquals("notifications/initialized", ((JsonRpcMessage.Notification) message).method());
    }

    @Test
    void parsesSuccessResponse() {
        JsonRpcMessage message = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"tools\":[]}}");
        assertInstanceOf(JsonRpcMessage.Response.class, message);
        JsonRpcMessage.Response response = (JsonRpcMessage.Response) message;
        assertEquals("42", response.id(), "数字 id 应归一为字符串");
        assertTrue(response.result().path("tools").isArray());
    }

    @Test
    void parsesErrorResponse() {
        JsonRpcMessage message = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":\"7\",\"error\":{\"code\":-32601,\"message\":\"Method not found\",\"data\":\"x\"}}");
        assertInstanceOf(JsonRpcMessage.Error.class, message);
        JsonRpcMessage.Error error = (JsonRpcMessage.Error) message;
        assertEquals("7", error.id());
        assertEquals(-32601, error.code());
        assertEquals("Method not found", error.message());
        assertEquals("x", error.data().asText());
    }

    @Test
    void rejectsInvalidJson() {
        McpException e = assertThrows(McpException.class, () -> JsonRpcCodec.parse("not-json"));
        assertEquals(McpException.Kind.PROTOCOL, e.kind());
    }

    @Test
    void rejectsMissingJsonrpcVersion() {
        assertThrows(McpException.class, () -> JsonRpcCodec.parse("{\"method\":\"tools/list\",\"id\":1}"));
    }

    @Test
    void rejectsMessageWithoutMethodOrId() {
        assertThrows(McpException.class, () -> JsonRpcCodec.parse("{\"jsonrpc\":\"2.0\"}"));
    }

    @Test
    void serializeRequestRoundTripsThroughParse() {
        String json = JsonRpcCodec.serializeRequest("tools/list", JSON.createObjectNode(), "9");
        JsonRpcMessage.Request request = (JsonRpcMessage.Request) JsonRpcCodec.parse(json);
        assertEquals("9", request.id());
        assertEquals("tools/list", request.method());
    }

    @Test
    void serializedMessagesContainJsonrpcAndCorrectFields() throws Exception {
        JsonNode request = JSON.readTree(JsonRpcCodec.serializeRequest(
                "tools/call", JSON.createObjectNode().put("a", 1), "5"));
        assertEquals("2.0", request.path("jsonrpc").asText());
        assertEquals("tools/call", request.path("method").asText());
        assertEquals(1, request.path("params").path("a").asInt());
        assertEquals("5", request.path("id").asText());

        JsonNode notification = JSON.readTree(JsonRpcCodec.serializeNotification("notifications/initialized", null));
        assertEquals("2.0", notification.path("jsonrpc").asText());
        assertFalse(notification.has("id"), "通知不应带 id");

        JsonNode response = JSON.readTree(JsonRpcCodec.serializeResponse("3", JSON.createObjectNode().put("ok", true)));
        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals("3", response.path("id").asText());
        assertTrue(response.path("result").path("ok").asBoolean());

        JsonNode error = JSON.readTree(JsonRpcCodec.serializeError("3", -32601, "Method not found", null));
        assertEquals("2.0", error.path("jsonrpc").asText());
        assertEquals(-32601, error.path("error").path("code").asInt());
        assertEquals("Method not found", error.path("error").path("message").asText());
    }

    @Test
    void serializeDispatchesByMessageType() {
        String json = JsonRpcCodec.serialize(new JsonRpcMessage.Request("tools/list", null, "1"));
        JsonRpcMessage.Request request = (JsonRpcMessage.Request) JsonRpcCodec.parse(json);
        assertEquals("tools/list", request.method());
        assertEquals("1", request.id());

        String errorJson = JsonRpcCodec.serialize(
                new JsonRpcMessage.Error("2", -32601, "Method not found", null));
        assertInstanceOf(JsonRpcMessage.Error.class, JsonRpcCodec.parse(errorJson));
    }
}
