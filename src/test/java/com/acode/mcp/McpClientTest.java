package com.acode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpClient 测试：内存假 transport（send 入队、按方法自动回注响应或由测试手动注入），
 * 覆盖握手顺序、版本协商、分页、isError、按 id 乱序匹配、超时、-32601 回执与 EOF 完成挂起。
 */
class McpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SHORT = Duration.ofMillis(200);

    @Test
    void initializeSendsInitializeThenNotificationInOrder() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        assertEquals(List.of("initialize", "notifications/initialized"),
                fake.methods(), "先发 initialize 请求，收到响应后再发 initialized 通知");
    }

    @Test
    void versionMismatchWarnsButContinues() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), "2024-11-05"));
        fake.respondTo("tools/list", req -> toolsListResponse(req.id(), 1, null));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize(); // 版本不一致不应抛错
        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size(), "版本不一致不应阻断后续工具发现");
    }

    @Test
    void listToolsPaginatesUntilNoCursor() throws Exception {
        FakeTransport fake = new FakeTransport();
        List<String> cursors = new CopyOnWriteArrayList<>();
        fake.onRequest(req -> {
            if ("tools/list".equals(req.method())) {
                cursors.add(req.params() != null ? req.params().path("cursor").asText("") : "");
            }
        });
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        fake.respondTo("tools/list", req -> {
            boolean hasCursor = req.params() != null && req.params().has("cursor");
            return hasCursor
                    ? toolsListResponse(req.id(), 1, null)
                    : toolsListResponse(req.id(), 2, "next");
        });
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(3, tools.size(), "首页 2 工具 + 第二页 1 工具 = 3");
        assertEquals(2, cursors.size());
        assertEquals("", cursors.get(0), "首页不带 cursor");
        assertEquals("next", cursors.get(1), "第二页携带首页 nextCursor");
    }

    @Test
    void listToolsStopsAfterMaxPages() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        fake.respondTo("tools/list", req -> toolsListResponse(req.id(), 1, "again"));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(McpClient.MAX_TOOL_LIST_PAGES, tools.size(), "永远返回 cursor 时最多拉取 10 页");
    }

    @Test
    void callToolReturnsResult() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        fake.respondTo("tools/call", req -> textResponse(req.id(), "hi"));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        JsonNode result = client.callTool("echo", JSON.createObjectNode().put("text", "hi"));
        assertEquals("hi", result.path("content").path(0).path("text").asText());
    }

    @Test
    void callToolIsErrorThrowsRemoteErrorWithText() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        fake.respondTo("tools/call", req -> errorResult(req.id(), "远端炸了"));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        McpException e = assertThrows(McpException.class,
                () -> client.callTool("bad", JSON.createObjectNode()));
        assertEquals(McpException.Kind.REMOTE, e.kind());
        assertTrue(e.getMessage().contains("远端炸了"), "错误消息应含远端错误文本");
    }

    @Test
    void outOfOrderResponsesMatchByRequestId() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        ConcurrentHashMap<String, String> idToText = new ConcurrentHashMap<>();
        CountDownLatch bothSent = new CountDownLatch(2);
        fake.onRequest(req -> {
            if ("tools/call".equals(req.method())) {
                idToText.put(req.id(), req.params().path("arguments").path("text").asText());
                bothSent.countDown();
            }
        });
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(
                () -> client.callTool("echo", JSON.createObjectNode().put("text", "a")));
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(
                () -> client.callTool("echo", JSON.createObjectNode().put("text", "b")));
        assertTrue(bothSent.await(5, TimeUnit.SECONDS), "两个 callTool 都应已发出");
        // 乱序注入：倒序响应，验证按 id 匹配不串台
        List<String> ids = List.copyOf(idToText.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            fake.respond(textResponse(ids.get(i), idToText.get(ids.get(i))));
        }
        assertEquals("a", first.get(5, TimeUnit.SECONDS)
                .path("content").path(0).path("text").asText());
        assertEquals("b", second.get(5, TimeUnit.SECONDS)
                .path("content").path(0).path("text").asText());
    }

    @Test
    void timeoutThrowsAndCleansPending() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        McpClient client = new McpClient(fake, SHORT);
        client.initialize();
        McpException e = assertThrows(McpException.class,
                () -> client.callTool("echo", JSON.createObjectNode()));
        assertEquals(McpException.Kind.TIMEOUT, e.kind());
        assertEquals(0, client.pendingCount(), "超时后 pending 应被清理");
    }

    @Test
    void serverRequestGetsMethodNotFoundReply() throws Exception {
        FakeTransport fake = new FakeTransport();
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        fake.respond(new JsonRpcMessage.Request("roots/list", JSON.createObjectNode(), "srv-1"));
        JsonRpcMessage reply = fake.sent.get(fake.sent.size() - 1);
        assertInstanceOf(JsonRpcMessage.Error.class, reply);
        JsonRpcMessage.Error error = (JsonRpcMessage.Error) reply;
        assertEquals(JsonRpcCodec.METHOD_NOT_FOUND, error.code());
        assertEquals("srv-1", error.id());
    }

    @Test
    void transportEofCompletesPendingRequests() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.respondTo("initialize", req -> initResponse(req.id(), McpClient.PROTOCOL_VERSION));
        McpClient client = new McpClient(fake, Duration.ofSeconds(5));
        client.initialize();
        CompletableFuture<JsonNode> call = CompletableFuture.supplyAsync(
                () -> client.callTool("echo", JSON.createObjectNode()));
        Thread.sleep(50); // 等 callTool 挂起
        fake.die();
        AtomicReference<Throwable> cause = new AtomicReference<>();
        call.exceptionally(e -> {
            cause.set(e instanceof java.util.concurrent.CompletionException ce ? ce.getCause() : e);
            return null;
        }).get(5, TimeUnit.SECONDS);
        assertTrue(cause.get() instanceof McpException, "EOF 后挂起请求应异常完成");
        assertEquals(McpException.Kind.CONNECTION, ((McpException) cause.get()).kind());
    }

    private static JsonRpcMessage.Response initResponse(String id, String protocolVersion) {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", protocolVersion);
        result.putObject("capabilities").putObject("tools");
        return new JsonRpcMessage.Response(id, result);
    }

    private static JsonRpcMessage.Response toolsListResponse(String id, int toolCount, String nextCursor) {
        ObjectNode result = JSON.createObjectNode();
        var tools = result.putArray("tools");
        for (int i = 0; i < toolCount; i++) {
            ObjectNode tool = tools.addObject();
            tool.put("name", "tool" + i);
            tool.put("description", "desc" + i);
            tool.putObject("inputSchema").put("type", "object");
        }
        if (nextCursor != null) {
            result.put("nextCursor", nextCursor);
        }
        return new JsonRpcMessage.Response(id, result);
    }

    private static JsonRpcMessage.Response textResponse(String id, String text) {
        ObjectNode result = JSON.createObjectNode();
        result.put("content", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", text)));
        return new JsonRpcMessage.Response(id, result);
    }

    private static JsonRpcMessage.Response errorResult(String id, String text) {
        ObjectNode result = JSON.createObjectNode();
        result.put("isError", true);
        result.put("content", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", text)));
        return new JsonRpcMessage.Response(id, result);
    }

    /** 内存假 transport：send 入队、按方法自动回注响应、支持测试手动注入与模拟死亡。 */
    private static final class FakeTransport implements Transport {

        private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
        private final Map<String, Function<JsonRpcMessage.Request, JsonRpcMessage>> responders
                = new ConcurrentHashMap<>();
        private final List<Consumer<JsonRpcMessage.Request>> requestHooks = new CopyOnWriteArrayList<>();
        private volatile Consumer<JsonRpcMessage> handler;
        private volatile Runnable termination;
        private volatile boolean alive = true;

        @Override
        public void start() {
        }

        @Override
        public void send(JsonRpcMessage message) {
            sent.add(message);
            if (message instanceof JsonRpcMessage.Request request) {
                requestHooks.forEach(hook -> hook.accept(request));
                Function<JsonRpcMessage.Request, JsonRpcMessage> responder = responders.get(request.method());
                if (responder != null) {
                    JsonRpcMessage reply = responder.apply(request);
                    if (reply != null) {
                        respond(reply);
                    }
                }
            }
        }

        @Override
        public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
            this.handler = handler;
        }

        @Override
        public void setTerminationHandler(Runnable termination) {
            this.termination = termination;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void close() {
        }

        void respondTo(String method, Function<JsonRpcMessage.Request, JsonRpcMessage> responder) {
            responders.put(method, responder);
        }

        void onRequest(Consumer<JsonRpcMessage.Request> hook) {
            requestHooks.add(hook);
        }

        void respond(JsonRpcMessage message) {
            Consumer<JsonRpcMessage> current = handler;
            if (current != null) {
                current.accept(message);
            }
        }

        void die() {
            alive = false;
            Runnable current = termination;
            if (current != null) {
                current.run();
            }
        }

        List<String> methods() {
            return sent.stream()
                    .map(m -> m instanceof JsonRpcMessage.Request r
                            ? r.method()
                            : m instanceof JsonRpcMessage.Notification n
                            ? n.method()
                            : m.getClass().getSimpleName())
                    .toList();
        }
    }
}
