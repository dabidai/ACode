package com.acode.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpTransport 测试：本地 HttpServer loopback 随机端口，按 Content-Type 用可编程 handler
 * 返回 JSON / SSE / 202 / 401，验证内容类型分流、会话头保持、错误与 isAlive 语义。
 */
class HttpTransportTest {

    private static final byte[] JSON_RESPONSE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}".getBytes(StandardCharsets.UTF_8);
    private static final String SSE_RESPONSE = """
            event: message
            data: {"jsonrpc":"2.0","id":"1","result":{"n":1}}

            event: message
            data: {"jsonrpc":"2.0","id":"2","result":{"n":2}}

            """;

    private static HttpServer server;
    private static String url;
    private static volatile String mode = "json";
    private static volatile String capturedSessionHeader;
    private static volatile String capturedContentType;
    private static volatile int requestCount;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestCount++;
            capturedSessionHeader = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
            capturedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String contentType = "application/json";
            byte[] body = JSON_RESPONSE;
            int status = 200;
            switch (mode) {
                case "sse" -> {
                    contentType = "text/event-stream";
                    body = SSE_RESPONSE.getBytes(StandardCharsets.UTF_8);
                }
                case "session" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "abc-123");
                }
                case "noct" -> contentType = null;
                case "202" -> {
                    body = new byte[0];
                    status = 202;
                }
                case "401" -> {
                    body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    status = 401;
                }
                default -> { }
            }
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        url = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void reset() {
        mode = "json";
        capturedSessionHeader = null;
        capturedContentType = null;
        requestCount = 0;
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static HttpTransport transport() {
        return new HttpTransport(url, Map.of(), Duration.ofSeconds(5));
    }

    @Test
    void jsonResponseParsesAndIsAliveTurnsTrue() throws Exception {
        HttpTransport transport = transport();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpcMessage> received = new AtomicReference<>();
        transport.setMessageHandler(m -> {
            received.set(m);
            latch.countDown();
        });
        transport.start();
        assertFalse(transport.isAlive(), "尚无请求时 isAlive 应为 false");
        transport.send(new JsonRpcMessage.Request("tools/list", null, "1"));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "应收到 JSON 响应");
        assertInstanceOf(JsonRpcMessage.Response.class, received.get());
        assertTrue(((JsonRpcMessage.Response) received.get()).result().path("ok").asBoolean());
        assertEquals("application/json", capturedContentType);
        assertTrue(transport.isAlive(), "成功请求后 isAlive 应为 true");
        transport.close();
    }

    @Test
    void sseResponseDispatchesAllMessageEvents() throws Exception {
        mode = "sse";
        HttpTransport transport = transport();
        List<JsonRpcMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        transport.setMessageHandler(m -> {
            received.add(m);
            latch.countDown();
        });
        transport.start();
        transport.send(new JsonRpcMessage.Request("tools/list", null, "1"));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "两个 message 事件都应被分发");
        assertEquals(2, received.size());
        JsonRpcMessage.Response first = (JsonRpcMessage.Response) received.get(0);
        JsonRpcMessage.Response second = (JsonRpcMessage.Response) received.get(1);
        assertEquals("1", first.id());
        assertEquals(1, first.result().path("n").asInt());
        assertEquals("2", second.id());
        assertEquals(2, second.result().path("n").asInt());
        transport.close();
    }

    @Test
    void sessionHeaderExtractedAndSentOnNextRequest() throws Exception {
        mode = "session";
        HttpTransport transport = transport();
        CountDownLatch latch = new CountDownLatch(1);
        transport.setMessageHandler(m -> latch.countDown());
        transport.start();
        transport.send(new JsonRpcMessage.Request("initialize", null, "1"));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "initialize 应收到响应");
        transport.send(new JsonRpcMessage.Request("tools/list", null, "2"));
        assertEquals("abc-123", capturedSessionHeader, "后续请求应携带 Mcp-Session-Id");
        transport.close();
    }

    @Test
    void notificationExpects202WithoutBody() {
        mode = "202";
        HttpTransport transport = transport();
        transport.start();
        transport.send(new JsonRpcMessage.Notification("notifications/initialized", null));
        assertTrue(transport.isAlive(), "202 通知请求应视为成功");
        transport.close();
    }

    @Test
    void non2xxThrowsConnectionFailedWithStatus() {
        mode = "401";
        HttpTransport transport = transport();
        transport.start();
        McpException e = assertThrows(McpException.class,
                () -> transport.send(new JsonRpcMessage.Request("tools/list", null, "1")));
        assertEquals(McpException.Kind.CONNECTION, e.kind());
        assertTrue(e.getMessage().contains("401"), "错误消息应含状态码");
        assertFalse(transport.isAlive(), "失败请求后 isAlive 应为 false");
        transport.close();
    }

    @Test
    void missingContentTypeDefaultsToJson() throws Exception {
        mode = "noct";
        HttpTransport transport = transport();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonRpcMessage> received = new AtomicReference<>();
        transport.setMessageHandler(m -> {
            received.set(m);
            latch.countDown();
        });
        transport.start();
        transport.send(new JsonRpcMessage.Request("tools/list", null, "1"));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Content-Type 缺失应按 JSON 解析");
        assertInstanceOf(JsonRpcMessage.Response.class, received.get());
        transport.close();
    }

    @Test
    void failedRequestAfterSuccessFlipsIsAlive() {
        mode = "json";
        HttpTransport transport = transport();
        transport.start();
        transport.send(new JsonRpcMessage.Request("tools/list", null, "1"));
        assertTrue(transport.isAlive());
        mode = "401";
        assertThrows(McpException.class,
                () -> transport.send(new JsonRpcMessage.Request("tools/list", null, "2")));
        assertFalse(transport.isAlive(), "一次失败请求后 isAlive 应为 false");
        transport.close();
    }
}
