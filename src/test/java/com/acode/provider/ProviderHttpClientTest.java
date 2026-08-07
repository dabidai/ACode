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
}
