package com.acode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 协议客户端：初始化握手（版本协商容错）→ initialized 通知 → 工具发现（含分页）→ 工具调用。
 * 请求-响应按 id 异步匹配：pending 表登记 CompletableFuture，响应经 {@link #dispatch} 按 id 完成。
 * 收到 server→client 请求统一回「不支持」错误（-32601）；传输终止时挂起请求全部异常完成。
 */
public class McpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final int MAX_TOOL_LIST_PAGES = 10;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Transport transport;
    private final Duration timeout;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public McpClient(Transport transport) {
        this(transport, DEFAULT_TIMEOUT);
    }

    public McpClient(Transport transport, Duration timeout) {
        this.transport = transport;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        transport.setMessageHandler(this::dispatch);
        transport.setTerminationHandler(this::failAllPending);
    }

    /** 握手：发 initialize、协议版本不一致仅警告不中断，随后发 initialized 通知。 */
    public void initialize() {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "acode");
        clientInfo.put("version", "0.1.0");
        JsonNode result = sendRequest("initialize", params);
        String serverVersion = result.path("protocolVersion").asText("");
        if (!PROTOCOL_VERSION.equals(serverVersion)) {
            System.err.println("警告：MCP server 协议版本 " + serverVersion
                    + " 与客户端 " + PROTOCOL_VERSION + " 不一致，继续连接");
        }
        sendNotification("notifications/initialized", JSON.createObjectNode());
    }

    /** 工具发现：tools/list 分页循环，直到无 nextCursor；超过 {@link #MAX_TOOL_LIST_PAGES} 页强制停止防死循环。 */
    public List<McpToolInfo> listTools() {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_LIST_PAGES; page++) {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = sendRequest("tools/list", params);
            for (JsonNode node : result.path("tools")) {
                tools.add(new McpToolInfo(
                        node.path("name").asText(""),
                        node.path("description").asText(""),
                        node.get("inputSchema")));
            }
            cursor = result.path("nextCursor").isTextual()
                    ? result.path("nextCursor").asText() : null;
            if (cursor == null) {
                break;
            }
        }
        return tools;
    }

    /** 工具调用：tools/call；远端 isError=true 转远端失败异常。 */
    public JsonNode callTool(String name, JsonNode arguments) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        if (arguments != null && !arguments.isNull()) {
            params.set("arguments", arguments);
        }
        JsonNode result = sendRequest("tools/call", params);
        if (result != null && result.path("isError").asBoolean(false)) {
            throw McpException.remoteError(extractErrorText(result));
        }
        return result;
    }

    public boolean isAlive() {
        return transport.isAlive();
    }

    /** 发送请求并等待响应（id 自增、pending 登记、超时兜底）。 */
    private JsonNode sendRequest(String method, JsonNode params) {
        String id = String.valueOf(nextId.incrementAndGet());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            transport.send(new JsonRpcMessage.Request(method, params, id));
        } catch (RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw McpException.timeout(method + " 未在 " + timeout.getSeconds() + "s 内收到响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw McpException.timeout(method + " 请求被中断");
        } catch (ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw McpException.remoteError(String.valueOf(cause));
        }
    }

    /** 发送通知（不期待响应）。 */
    private void sendNotification(String method, JsonNode params) {
        transport.send(new JsonRpcMessage.Notification(method, params));
    }

    /** 远端消息分发：响应按 id 完成 pending；server→client 请求回「不支持」错误；通知忽略。 */
    void dispatch(JsonRpcMessage message) {
        switch (message) {
            case JsonRpcMessage.Response response -> completePending(response.id(), response.result());
            case JsonRpcMessage.Error error -> failPending(error.id(), McpException.remoteError(error.message()));
            case JsonRpcMessage.Request request -> {
                try {
                    transport.send(new JsonRpcMessage.Error(request.id(),
                            JsonRpcCodec.METHOD_NOT_FOUND, "Method not found", null));
                } catch (RuntimeException ignored) {
                    // 回执失败不影响主流程
                }
            }
            case JsonRpcMessage.Notification notification -> {
                // 服务端主动通知（如 logging/levelChanged）忽略
            }
        }
    }

    private void completePending(String id, JsonNode result) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) {
            future.complete(result);
        }
    }

    private void failPending(String id, McpException error) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    /** 传输终止（EOF/进程退出）：挂起请求全部异常完成。 */
    private void failAllPending() {
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(McpException.connectionFailed("传输已终止"));
        }
        pending.clear();
    }

    private static String extractErrorText(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.isEmpty() ? "工具执行失败（isError）" : sb.toString();
    }

    public void close() {
        transport.close();
        failAllPending();
    }

    /** 测试可见：当前挂起请求数。 */
    int pendingCount() {
        return pending.size();
    }
}
