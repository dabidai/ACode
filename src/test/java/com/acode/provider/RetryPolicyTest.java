package com.acode.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private static final AtomicInteger requests = new AtomicInteger();
    private static final List<Long> arrivals = new ArrayList<>();
    private static volatile int respondStatus = 200;
    private static HttpServer server;
    private static String url;
    private static final String BODY = "{\"q\":1}";
    private static final Map<String, String> HEADERS = Map.of();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            arrivals.add(System.currentTimeMillis());
            byte[] body = respondStatus == 200
                    ? "ok".getBytes(StandardCharsets.UTF_8)
                    : "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondStatus, body.length);
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
        requests.set(0);
        arrivals.clear();
        respondStatus = 200;
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void mock返回429时恰好重试3次间隔约1s2s4s后抛RateLimitException() {
        respondStatus = 429;
        long start = System.currentTimeMillis();
        assertThrows(RateLimitException.class, () -> ProviderHttpClient.send(url, BODY, HEADERS));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(4, requests.get(), "1 次初始请求 + 3 次重试");
        assertTrue(elapsed >= 7000, "退避累计应 ≥ 7s，实际 " + elapsed + "ms");
        long d1 = arrivals.get(1) - arrivals.get(0);
        long d2 = arrivals.get(2) - arrivals.get(1);
        long d3 = arrivals.get(3) - arrivals.get(2);
        assertBackoff(d1, 1000, "第 1 次重试间隔");
        assertBackoff(d2, 2000, "第 2 次重试间隔");
        assertBackoff(d3, 4000, "第 3 次重试间隔");
    }

    @Test
    void mock返回500时重试3次后抛ServerException() {
        respondStatus = 500;
        assertThrows(ServerException.class, () -> ProviderHttpClient.send(url, BODY, HEADERS));
        assertEquals(4, requests.get());
    }

    @Test
    void mock返回200时只发一次请求无重试() throws IOException {
        respondStatus = 200;
        ProviderHttpClient.Result result = ProviderHttpClient.send(url, BODY, HEADERS);
        assertEquals(200, result.status());
        result.body().close();
        assertEquals(1, requests.get());
    }

    @Test
    void mock返回401时不重试直接抛AuthException() {
        respondStatus = 401;
        assertThrows(AuthException.class, () -> ProviderHttpClient.send(url, BODY, HEADERS));
        assertEquals(1, requests.get());
    }

    @Test
    void 地址不可达时抛NetworkException且耗时不超过读超时加退避() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = s.getLocalPort();
        }
        String deadUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + deadPort + "/";
        long start = System.currentTimeMillis();
        assertThrows(NetworkException.class, () -> ProviderHttpClient.send(deadUrl, BODY, HEADERS));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 60_000 + 8_000, "总耗时不应超过读超时(60s)+退避，实际 " + elapsed + "ms");
    }

    /** Thread.sleep 只会超时不会提前，故下界按 ±0.5s 严格校验，上界放宽到 +1.5s */
    private static void assertBackoff(long actual, long expected, String label) {
        long lo = expected - 500;
        long hi = expected + 1500;
        assertTrue(actual >= lo && actual <= hi,
                label + "期望 " + expected + "ms，实际 " + actual + "ms");
    }
}
