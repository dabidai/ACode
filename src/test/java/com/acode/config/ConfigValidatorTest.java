package com.acode.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {

    private AppConfig valid() {
        AppConfig config = new AppConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-sonnet-4-6");
        config.setBaseUrl("https://api.anthropic.com");
        config.setApiKey("test-key");
        return config;
    }

    @Test
    void 合法配置通过并填充默认窗口() {
        AppConfig config = valid();
        ConfigValidator.validate(config, "test.yaml");
        assertEquals(ConfigValidator.DEFAULT_MAX_CONTEXT_TOKENS, config.getMaxContextTokens());
    }

    @Test
    void protocol非法值报错含枚举说明() {
        AppConfig config = valid();
        config.setProtocol("foo");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("protocol"));
        assertTrue(e.getMessage().contains("anthropic/openai"));
    }

    @Test
    void 缺apiKey报错() {
        AppConfig config = valid();
        config.setApiKey(null);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("api_key"));
    }

    @Test
    void baseUrl非法报错() {
        AppConfig config = valid();
        config.setBaseUrl("not a url");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("base_url"));
    }

    @Test
    void baseUrl非http协议报错() {
        AppConfig config = valid();
        config.setBaseUrl("ftp://files.example.com");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("base_url"));
    }

    @Test
    void 缺model报错() {
        AppConfig config = valid();
        config.setModel("   ");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("model"));
    }

    @Test
    void 窗口上限非正数报错() {
        AppConfig config = valid();
        config.setMaxContextTokens(0);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("max_context_tokens"));
    }

    @Test
    void 空配置报protocol必填() {
        AppConfig config = new AppConfig();
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("protocol 必填"));
    }

    @Test
    void defaultMaxIterationsApplied() {
        AppConfig config = valid();
        ConfigValidator.validate(config, "test.yaml");
        assertEquals(ConfigValidator.DEFAULT_MAX_ITERATIONS, config.getMaxIterations());
    }

    @Test
    void nonPositiveMaxIterationsRejected() {
        AppConfig config = valid();
        config.setMaxIterations(0);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("max_iterations"));
    }

    @Test
    void permissionModeAllValidValuesPass() {
        for (String mode : new String[]{"default", "acceptEdits", "plan", "bypassPermissions"}) {
            AppConfig config = valid();
            config.setPermissionMode(mode);
            ConfigValidator.validate(config, "test.yaml");
        }
    }

    @Test
    void permissionModeIllegalValueThrows() {
        AppConfig config = valid();
        config.setPermissionMode("yolo");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigValidator.validate(config, "test.yaml"));
        assertTrue(e.getMessage().contains("permission_mode"));
        assertTrue(e.getMessage().contains("yolo"));
        assertTrue(e.getMessage().contains("test.yaml"), "错误应带文件路径定位");
    }

    @Test
    void permissionModeNullIsAccepted() {
        AppConfig config = valid();
        config.setPermissionMode(null);
        ConfigValidator.validate(config, "test.yaml");
    }
}
