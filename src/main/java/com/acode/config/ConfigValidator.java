package com.acode.config;

import java.net.URI;
import java.util.Set;

/**
 * 校验配置合法性：protocol 枚举、必填字段、base_url 格式、上下文窗口上限。
 * 失败消息带字段名，供用户定位。
 */
public class ConfigValidator {

    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 128_000;
    public static final int DEFAULT_MAX_ITERATIONS = 20;
    private static final Set<String> PROTOCOLS = Set.of("anthropic", "openai");

    public static void validate(AppConfig config, String source) {
        if (isBlank(config.getProtocol())) {
            throw err(source, "protocol 必填（anthropic/openai）");
        }
        if (!PROTOCOLS.contains(config.getProtocol())) {
            throw err(source, "protocol 必须是 anthropic/openai，当前值 " + config.getProtocol());
        }
        if (isBlank(config.getModel())) {
            throw err(source, "model 必填");
        }
        if (isBlank(config.getApiKey())) {
            throw err(source, "api_key 必填");
        }
        if (isBlank(config.getBaseUrl())) {
            throw err(source, "base_url 必填");
        }
        try {
            URI uri = URI.create(config.getBaseUrl());
            if (!uri.isAbsolute() || !Set.of("http", "https").contains(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw err(source, "base_url 不是合法 http(s) URL：" + config.getBaseUrl());
        }

        if (config.getMaxContextTokens() == null) {
            config.setMaxContextTokens(DEFAULT_MAX_CONTEXT_TOKENS);
        } else if (config.getMaxContextTokens() <= 0) {
            throw err(source, "max_context_tokens 必须是正整数，当前值 " + config.getMaxContextTokens());
        }

        if (config.getMaxIterations() == null) {
            config.setMaxIterations(DEFAULT_MAX_ITERATIONS);
        } else if (config.getMaxIterations() <= 0) {
            throw err(source, "max_iterations 必须是正整数，当前值 " + config.getMaxIterations());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ConfigException err(String source, String message) {
        return new ConfigException(source + ": " + message);
    }
}
