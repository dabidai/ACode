package com.acode.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderHttpClientTest {

    @Test
    void 状态码分类正确() {
        assertInstanceOf(AuthException.class, ProviderHttpClient.classify(401, "{}"));
        assertInstanceOf(AuthException.class, ProviderHttpClient.classify(403, "{}"));
        assertInstanceOf(RateLimitException.class, ProviderHttpClient.classify(429, "{}"));
        assertInstanceOf(ServerException.class, ProviderHttpClient.classify(500, "{}"));
        assertInstanceOf(ServerException.class, ProviderHttpClient.classify(502, "{}"));
        assertInstanceOf(InvalidRequestException.class, ProviderHttpClient.classify(400, "{}"));
        assertInstanceOf(InvalidRequestException.class, ProviderHttpClient.classify(422, "{}"));
    }

    @Test
    void 从错误体提取message() {
        ProviderException e = ProviderHttpClient.classify(429,
                "{\"error\":{\"message\":\"too many requests\",\"type\":\"rate_limit\"}}");
        assertTrue(e.getMessage().contains("too many requests"));
    }

    @Test
    void 非JSON错误体退回原文首行() {
        ProviderException e = ProviderHttpClient.classify(500, "Internal Server Error");
        assertTrue(e.getMessage().contains("Internal Server Error"));
    }

    @Test
    void classifyMapsServerErrorsToServerException() {
        assertInstanceOf(ServerException.class, ProviderHttpClient.classify(500, "boom"), "500 应为 ServerException");
        assertInstanceOf(ServerException.class, ProviderHttpClient.classify(503, "unavailable"), "503 应为 ServerException");
        assertInstanceOf(ServerException.class, ProviderHttpClient.classify(504, "gateway"), "504 应为 ServerException");
        ProviderException e = ProviderHttpClient.classify(500, "{\"error\":{\"message\":\"server exploded\"}}");
        assertTrue(e.getMessage().contains("server exploded"), "错误体 message 应透传");
        assertTrue(e.getMessage().contains("500"), "错误文本应含状态码");
    }
}
