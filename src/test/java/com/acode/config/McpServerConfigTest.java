package com.acode.config;

import com.acode.tool.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConfigTest {

    private static final String SOURCE = "test.yaml";

    @Test
    void stdioMissingCommandThrowsWithLocation() {
        Map<String, Object> yaml = Map.of("type", "stdio");
        ConfigException e = assertThrows(ConfigException.class,
                () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
        assertTrue(e.getMessage().contains("mcp_servers.srv"), "错误消息应带 mcp_servers.<name> 定位");
    }

    @Test
    void httpMissingUrlThrows() {
        Map<String, Object> yaml = Map.of("type", "http");
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
    }

    @Test
    void invalidPermissionThrows() {
        Map<String, Object> yaml = Map.of("type", "stdio", "command", "npx", "permission", "sudo");
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
    }

    @Test
    void negativeTimeoutThrows() {
        Map<String, Object> yaml = Map.of("type", "stdio", "command", "npx", "timeout", -5);
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
    }

    @Test
    void invalidTypeThrows() {
        Map<String, Object> yaml = Map.of("type", "websocket", "command", "npx");
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
    }

    @Test
    void invalidNameCharThrows() {
        Map<String, Object> yaml = Map.of("type", "stdio", "command", "npx");
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("bad name!", yaml, SOURCE));
    }

    @Test
    void defaultsTimeoutPermissionAndEnabled() {
        Map<String, Object> yaml = Map.of("type", "stdio", "command", "npx", "args", List.of("-y", "x"));
        McpServerConfig config = McpServerConfig.fromYaml("srv", yaml, SOURCE);
        assertEquals(60, config.timeoutSeconds(), "超时默认 60 秒");
        assertEquals(Permission.EXEC, config.permission(), "权限档默认 exec（最严档）");
        assertTrue(config.enabled(), "开关默认开");
    }

    @Test
    void stdioCommandAndArgsAssembled() {
        Map<String, Object> yaml = Map.of("type", "stdio", "command", "npx",
                "args", List.of("-y", "@x/y"), "env", Map.of("KEY", "val"));
        McpServerConfig config = McpServerConfig.fromYaml("srv", yaml, SOURCE);
        assertEquals(List.of("npx", "-y", "@x/y"), config.command());
        assertEquals("val", config.env().get("KEY"));
    }

    @Test
    void httpUrlHeadersAndOverridesParsed() {
        Map<String, Object> yaml = Map.of("type", "http", "url", "https://x.com/mcp",
                "headers", Map.of("Authorization", "Bearer t"),
                "timeout", 30, "permission", "read", "enabled", false);
        McpServerConfig config = McpServerConfig.fromYaml("srv", yaml, SOURCE);
        assertEquals("https://x.com/mcp", config.url());
        assertEquals("Bearer t", config.headers().get("Authorization"));
        assertEquals(30, config.timeoutSeconds());
        assertEquals(Permission.READ, config.permission());
        assertFalse(config.enabled());
    }

    @Test
    void invalidUrlThrows() {
        Map<String, Object> yaml = Map.of("type", "http", "url", "not-a-url");
        assertThrows(ConfigException.class, () -> McpServerConfig.fromYaml("srv", yaml, SOURCE));
    }
}
