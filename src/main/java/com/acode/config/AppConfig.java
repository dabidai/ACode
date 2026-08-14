package com.acode.config;

/**
 * ACode 配置模型。YAML 字段 snake_case 对应 Java 字段：
 * protocol / model / base_url / api_key / max_context_tokens / max_iterations
 */
public class AppConfig {

    private String protocol;
    private String model;
    private String baseUrl;
    private String apiKey;
    private Integer maxContextTokens;
    private Integer maxIterations;
    private Boolean tee;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(Integer maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    /** 诊断 tee 开关：配置 tee: true 或环境变量 ACODE_TEE 存在（兜底，便于不改配置快速开）。 */
    public boolean isTeeEnabled() {
        return Boolean.TRUE.equals(tee) || System.getenv("ACODE_TEE") != null;
    }

    public Boolean getTee() {
        return tee;
    }

    public void setTee(Boolean tee) {
        this.tee = tee;
    }
}
