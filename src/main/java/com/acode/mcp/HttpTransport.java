package com.acode.mcp;

import com.acode.sse.SseParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streamable HTTP 传输：每次请求同步 POST，响应体按 Content-Type 分流——
 * {@code text/event-stream} 用 SseParser 逐事件解析（只取 message 事件，一次响应可分发多条），
 * 否则整个 body 按单条消息解析；Content-Type 缺失默认按 JSON。
 * initialize 响应提取 {@code Mcp-Session-Id} 头并回传后续请求；通知期望 202 无 body；
 * 非 2xx 读错误体（限 8KB）抛连接失败。isAlive 依据最近一次请求成败。
 */
public class HttpTransport implements Transport {

    private static final int ERROR_BODY_LIMIT = 8192;
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final String url;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<Boolean> lastRequestOk = new AtomicReference<>(false);

    private volatile Consumer<JsonRpcMessage> messageHandler = message -> { };

    public HttpTransport(String url, Map<String, String> headers, Duration timeout) {
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void start() {
        // HTTP 无显式启动步骤；首次请求即建连
    }

    @Override
    public void send(JsonRpcMessage message) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonRpcCodec.serialize(message), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        String session = sessionId.get();
        if (session != null) {
            builder.header(SESSION_HEADER, session);
        }

        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            lastRequestOk.set(false);
            throw McpException.connectionFailed("HTTP 请求失败：" + e.getMessage());
        }

        int status = response.statusCode();
        if (status == 202) {
            try (InputStream body = response.body()) {
                // 通知：期望无 body，仅排空关闭
            } catch (IOException ignored) {
            }
            lastRequestOk.set(true);
            return;
        }
        if (status < 200 || status >= 300) {
            String errorBody = readLimited(response.body());
            lastRequestOk.set(false);
            throw McpException.connectionFailed("HTTP " + status + "：" + firstLine(errorBody));
        }

        String sessionHeader = response.headers().firstValue(SESSION_HEADER).orElse(null);
        if (sessionHeader != null && !sessionHeader.isBlank()) {
            sessionId.set(sessionHeader);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try (InputStream body = response.body()) {
            if (contentType.contains("text/event-stream")) {
                SseParser.parse(body, (eventType, data) -> {
                    if ("message".equals(eventType) || eventType.isEmpty()) {
                        dispatchText(data);
                    }
                });
            } else {
                String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                dispatchText(text);
            }
        } catch (IOException e) {
            lastRequestOk.set(false);
            throw McpException.connectionFailed("读取 HTTP 响应失败：" + e.getMessage());
        }
        lastRequestOk.set(true);
    }

    private void dispatchText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            JsonRpcMessage message = JsonRpcCodec.parse(text);
            Consumer<JsonRpcMessage> handler = messageHandler;
            if (handler != null) {
                handler.accept(message);
            }
        } catch (McpException e) {
            // 单帧解析失败跳过，不影响同批其他事件
        }
    }

    @Override
    public void setMessageHandler(Consumer<JsonRpcMessage> handler) {
        if (handler != null) {
            this.messageHandler = handler;
        }
    }

    @Override
    public boolean isAlive() {
        return Boolean.TRUE.equals(lastRequestOk.get());
    }

    @Override
    public void close() {
        // HTTP 每次请求独立 POST，无进程/长连接需清理
    }

    private static String readLimited(InputStream in) {
        try (in) {
            return new String(in.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String firstLine(String body) {
        String first = body.isBlank() ? "" : body.strip().lines().findFirst().orElse("");
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }
}
