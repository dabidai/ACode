package com.acode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path globalFile() {
        return tempDir.resolve("global-config.yaml");
    }

    private Path projectDir() {
        return tempDir.resolve("project");
    }

    private Path projectConfig() {
        return projectDir().resolve(".acode/config.yaml");
    }

    private Path noCCSwitch() {
        return tempDir.resolve("no-such-ccswitch/settings.json");
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private AppConfig validGlobal() throws IOException {
        write(globalFile(), """
                protocol: anthropic
                model: claude-sonnet-4-6
                base_url: https://api.anthropic.com
                api_key: global-key
                """);
        return ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
    }

    @Test
    void 两级配置都缺失时使用内置默认() {
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("openai", config.getProtocol());
        assertEquals("agnes-2.0-flash", config.getModel());
        assertEquals("https://apihub.agnes-ai.com/v1", config.getBaseUrl());
        assertTrue(config.getApiKey() != null && !config.getApiKey().isBlank(), "api_key 不应为空");
        assertEquals(ConfigValidator.DEFAULT_MAX_CONTEXT_TOKENS, config.getMaxContextTokens());
        assertEquals(ConfigValidator.DEFAULT_MAX_ITERATIONS, config.getMaxIterations());
    }

    @Test
    void 仅项目级配置存在时生效() throws IOException {
        write(projectConfig(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: project-key
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("openai", config.getProtocol());
        assertEquals("deepseek-v4-flash", config.getModel());
        assertEquals("project-key", config.getApiKey());
    }

    @Test
    void 项目级不存在时全局配置单独生效() throws IOException {
        AppConfig config = validGlobal();
        assertEquals("anthropic", config.getProtocol());
        assertEquals("claude-sonnet-4-6", config.getModel());
        assertEquals("global-key", config.getApiKey());
        assertEquals(ConfigValidator.DEFAULT_MAX_CONTEXT_TOKENS, config.getMaxContextTokens());
    }

    @Test
    void 项目级只覆盖model其余沿用全局() throws IOException {
        validGlobal();
        write(projectConfig(), "model: gpt-4o\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("gpt-4o", config.getModel());
        assertEquals("global-key", config.getApiKey());
        assertEquals("https://api.anthropic.com", config.getBaseUrl());
        assertEquals("anthropic", config.getProtocol());
    }

    @Test
    void 项目级覆盖baseUrl和窗口上限() throws IOException {
        validGlobal();
        write(projectConfig(), "base_url: https://proxy.example.com\nmax_context_tokens: 64000\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("https://proxy.example.com", config.getBaseUrl());
        assertEquals(64000, config.getMaxContextTokens());
    }

    @Test
    void 项目级未知字段报错() throws IOException {
        validGlobal();
        write(projectConfig(), "max_context_token: 100\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains("未知配置项"));
        assertTrue(e.getMessage().contains("max_context_token"));
    }

    @Test
    void thinkingFlagParsedWhenTrue() throws IOException {
        write(projectConfig(), "thinking: true\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals(Boolean.TRUE, config.getThinking());
    }

    @Test
    void thinkingNonBooleanRejected() throws IOException {
        write(projectConfig(), "thinking: maybe\n");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
    }

    @Test
    void 项目级protocol非法报错定位到项目文件() throws IOException {
        validGlobal();
        write(projectConfig(), "protocol: foo\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains(projectConfig().toString()));
        assertTrue(e.getMessage().contains("protocol"));
    }

    @Test
    void 全局窗口上限类型错误报错() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: gpt-4o
                base_url: https://api.openai.com
                api_key: global-key
                max_context_tokens: abc
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains("max_context_tokens"));
    }

    @Test
    void 空文件视为无覆盖内置默认生效() throws IOException {
        write(globalFile(), "");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("openai", config.getProtocol());
        assertEquals("agnes-2.0-flash", config.getModel());
    }

    @Test
    void globalDefaultsMaxIterationsToTwenty() throws IOException {
        AppConfig config = validGlobal();
        assertEquals(20, config.getMaxIterations());
    }

    @Test
    void projectLevelOverridesMaxIterations() throws IOException {
        validGlobal();
        write(projectConfig(), "max_iterations: 5\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals(5, config.getMaxIterations());
    }

    @Test
    void nonNumericMaxIterationsRejected() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: gpt-4o
                base_url: https://api.openai.com
                api_key: global-key
                max_iterations: abc
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains("max_iterations"));
    }

    @Test
    void 内置默认上项目级只覆盖model() throws IOException {
        write(projectConfig(), "model: gpt-4o\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("gpt-4o", config.getModel());
        assertEquals("openai", config.getProtocol());
        assertEquals("https://apihub.agnes-ai.com/v1", config.getBaseUrl());
        assertTrue(config.getApiKey() != null && !config.getApiKey().isBlank());
    }

    @Test
    void tee配置解析生效() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: global-key
                tee: true
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertTrue(config.isTeeEnabled());
    }

    @Test
    void tee非布尔报错() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: global-key
                tee: abc
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains("tee 必须是 true/false"));
    }

    @Test
    void permissionModeReadFromConfig() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: global-key
                permission_mode: acceptEdits
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), noCCSwitch());
        assertEquals("acceptEdits", config.getPermissionMode());
    }

    @Test
    void permissionModeTypeErrorRejected() throws IOException {
        write(globalFile(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: global-key
                permission_mode: 123
                """);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir(), noCCSwitch()));
        assertTrue(e.getMessage().contains("permission_mode 必须是字符串"));
    }

    @Test
    void permissionModeAbsentDefaultsToNull() throws IOException {
        AppConfig config = validGlobal();
        assertEquals(null, config.getPermissionMode());
    }

    private Path ccSwitchFile() {
        return tempDir.resolve("ccswitch/settings.json");
    }

    private void writeCCSwitch(String content) throws IOException {
        Files.createDirectories(ccSwitchFile().getParent());
        Files.writeString(ccSwitchFile(), content);
    }

    @Test
    void ccSwitch存在且无手动配置时使用ccSwitch值() throws IOException {
        writeCCSwitch("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721", "ANTHROPIC_AUTH_TOKEN": "proxy-token"}}
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("anthropic", config.getProtocol());
        assertEquals("http://127.0.0.1:15721", config.getBaseUrl());
        assertEquals("proxy-token", config.getApiKey());
        assertTrue(config.isCcSwitchDetected());
    }

    @Test
    void ccSwitch存在且全局覆盖model时保留ccSwitch的baseUrl和apiKey() throws IOException {
        writeCCSwitch("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721", "ANTHROPIC_AUTH_TOKEN": "proxy-token"}}
                """);
        write(globalFile(), "model: gpt-4o\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("gpt-4o", config.getModel());
        assertEquals("http://127.0.0.1:15721", config.getBaseUrl());
        assertEquals("proxy-token", config.getApiKey());
        assertEquals("anthropic", config.getProtocol());
    }

    @Test
    void ccSwitch存在且全局覆盖apiKey时使用全局apiKey() throws IOException {
        writeCCSwitch("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721", "ANTHROPIC_AUTH_TOKEN": "proxy-token"}}
                """);
        write(globalFile(), """
                api_key: manual-key
                model: test-model
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("manual-key", config.getApiKey());
        assertEquals("http://127.0.0.1:15721", config.getBaseUrl());
    }

    @Test
    void ccSwitch模型映射生效() throws IOException {
        writeCCSwitch("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": "claude-sonnet-4-6[1M]",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME": "agnes-2.0-flash"
                  }
                }
                """);
        write(globalFile(), "model: claude-sonnet-4-6[1M]\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("agnes-2.0-flash", config.getModel());
    }

    @Test
    void ccSwitch模型映射不匹配时保持不变() throws IOException {
        writeCCSwitch("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": "claude-sonnet-4-6[1M]",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME": "agnes-2.0-flash"
                  }
                }
                """);
        write(globalFile(), "model: deepseek-v4-flash\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("deepseek-v4-flash", config.getModel());
    }

    @Test
    void reloadWithCCSwitch替换ccSwitch层保留手动配置() throws IOException {
        writeCCSwitch("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721", "ANTHROPIC_AUTH_TOKEN": "old-token"}}
                """);
        write(globalFile(), "model: my-model\n");
        AppConfig original = ConfigLoader.load(globalFile(), projectDir(), ccSwitchFile());
        assertEquals("http://127.0.0.1:15721", original.getBaseUrl());
        assertEquals("old-token", original.getApiKey());
        assertEquals("my-model", original.getModel());

        CCSwitchConfig newCCSwitch = new CCSwitchConfig(
                "http://127.0.0.1:9999", "new-token", "new-model", java.util.Map.of());
        AppConfig reloaded = ConfigLoader.reloadWithCCSwitch(globalFile(), projectDir(), newCCSwitch);
        assertEquals("http://127.0.0.1:9999", reloaded.getBaseUrl());
        assertEquals("new-token", reloaded.getApiKey());
        assertEquals("my-model", reloaded.getModel());
        assertEquals("anthropic", reloaded.getProtocol());
    }
}
