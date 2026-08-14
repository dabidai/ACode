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
        return ConfigLoader.load(globalFile(), projectDir());
    }

    @Test
    void 全局配置缺失时报错并指向示例() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir()));
        assertTrue(e.getMessage().contains("未找到配置文件"));
        assertTrue(e.getMessage().contains("examples/config.yaml"));
    }

    @Test
    void 仅项目级配置存在时生效() throws IOException {
        write(projectConfig(), """
                protocol: openai
                model: deepseek-v4-flash
                base_url: https://api.deepseek.com/v1
                api_key: project-key
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
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
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals("gpt-4o", config.getModel());
        assertEquals("global-key", config.getApiKey());
        assertEquals("https://api.anthropic.com", config.getBaseUrl());
        assertEquals("anthropic", config.getProtocol());
    }

    @Test
    void 项目级覆盖baseUrl和窗口上限() throws IOException {
        validGlobal();
        write(projectConfig(), "base_url: https://proxy.example.com\nmax_context_tokens: 64000\n");
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals("https://proxy.example.com", config.getBaseUrl());
        assertEquals(64000, config.getMaxContextTokens());
    }

    @Test
    void 项目级未知字段报错() throws IOException {
        validGlobal();
        write(projectConfig(), "max_context_token: 100\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir()));
        assertTrue(e.getMessage().contains("未知配置项"));
        assertTrue(e.getMessage().contains("max_context_token"));
    }

    @Test
    void 项目级protocol非法报错定位到项目文件() throws IOException {
        validGlobal();
        write(projectConfig(), "protocol: foo\n");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir()));
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
                () -> ConfigLoader.load(globalFile(), projectDir()));
        assertTrue(e.getMessage().contains("max_context_tokens"));
    }

    @Test
    void 空文件视为空配置报必填错误() throws IOException {
        write(globalFile(), "");
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(globalFile(), projectDir()));
        assertTrue(e.getMessage().contains("protocol 必填"));
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
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
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
                () -> ConfigLoader.load(globalFile(), projectDir()));
        assertTrue(e.getMessage().contains("max_iterations"));
    }
}
