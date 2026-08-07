package com.acode.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 统一 HTTP 发送与错误分类。200 返回响应体输入流（调用方解析 SSE）；
 * 非 200 按状态码分类抛 ProviderException。重试逻辑见 T6。
 */
public final class ProviderHttpClient {

    private static final int ERROR_BODY_LIMIT = 8192;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ProviderHttpClient() {
    }

    public record Result(int status, InputStream body) {
    }

    public static Result send(String url, String json, Map<String, String> headers) {
        for (int attempt = 1; ; attempt++) {
            try {
                return doSend(url, json, headers);
            } catch (ProviderException e) {
                if (attempt > RetryPolicy.MAX_RETRIES || !RetryPolicy.isRetryable(e)) {
                    throw e;
                }
                sleep(RetryPolicy.backoffMs(attempt));
            }
        }
    }

    /** 单次请求：200 返回响应体输入流，非 200 按状态码分类抛异常 */
    private static Result doSend(String url, String json, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        try {
            HttpResponse<InputStream> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String errorBody = readLimited(response.body());
                throw classify(response.statusCode(), errorBody);
            }
            return new Result(response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new NetworkException("网络请求失败：" + e.getMessage(), e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkException("重试等待被中断", e);
        }
    }

    /** 按 HTTP 状态码把失败响应分类为对应异常 */
    public static ProviderException classify(int status, String body) {
        String detail = extractMessage(body);
        return switch (status) {
            case 401, 403 -> new AuthException("认证失败（HTTP " + status + "）：" + detail);
            case 429 -> new RateLimitException("限流（HTTP 429）：" + detail);
            default -> status >= 500
                    ? new ServerException("服务端错误（HTTP " + status + "）：" + detail)
                    : new InvalidRequestException("请求错误（HTTP " + status + "）：" + detail);
        };
    }

    private static String readLimited(InputStream in) {
        try (in) {
            return new String(in.readNBytes(ERROR_BODY_LIMIT), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "（读取错误详情失败）";
        }
    }

    private static String extractMessage(String body) {
        try {
            JsonNode message = JSON.readTree(body).path("error").path("message");
            if (!message.isMissingNode() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 错误体，退回原文
        }
        String firstLine = body.isBlank() ? "" : body.strip().lines().findFirst().orElse("");
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "…" : firstLine;
    }
}
