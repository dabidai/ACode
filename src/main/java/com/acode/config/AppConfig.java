package com.acode.config;

import java.util.Map;

/**
 * ACode 配置模型。YAML 字段 snake_case 对应 Java 字段：
 * protocol / model / base_url / api_key / max_context_tokens / max_iterations / tee / thinking /
 * permission_mode / memory_auto / mcp_servers
 */
public class AppConfig {

    private String protocol;
    private String model;
    private String baseUrl;
    private String apiKey;
    private Integer maxContextTokens;
    private Integer maxIterations;
    private Boolean tee;
    private Boolean thinking;
    private String permissionMode;
    private Boolean memoryAuto;
    private Map<String, McpServerConfig> mcpServers;

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

    /** thinking 开关：null 时由装配层按 protocol 推断（anthropic→true、openai→false）。 */
    public Boolean getThinking() {
        return thinking;
    }

    public void setThinking(Boolean thinking) {
        this.thinking = thinking;
    }

    /** 启动默认权限模式；null 由装配层按 default 处理。 */
    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    /** 自动记忆提取开关：缺省（null）视为开启；显式 false 时每轮结束不再有后台提取调用。 */
    public boolean isMemoryAutoEnabled() {
        return !Boolean.FALSE.equals(memoryAuto);
    }

    public Boolean getMemoryAuto() {
        return memoryAuto;
    }

    public void setMemoryAuto(Boolean memoryAuto) {
        this.memoryAuto = memoryAuto;
    }

    /** MCP server 列表（名→配置）；未配置时为空映射。 */
    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers == null ? Map.of() : mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers;
    }
}
