package com.acode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigLoader 的 mcp_servers 段解析：三段加载合并、按名整项覆盖、未知键白名单、非映射报错、
 * ConfigValidator 不受影响。
 */
class ConfigLoaderMcpTest {

    @TempDir
    Path tempDir;

    private Path globalFile() {
        return tempDir.resolve("global.yaml");
    }

    private Path projectDir() {
        return tempDir.resolve("project");
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void parsesMcpServersFromConfig() throws IOException {
        write(globalFile(), """
                mcp_servers:
                  a:
                    type: stdio
                    command: npx
                    args: [-y, "@x/a"]
                  b:
                    type: http
                    url: https://x.com/mcp
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals(2, config.getMcpServers().size());
        assertEquals(List.of("npx", "-y", "@x/a"), config.getMcpServers().get("a").command());
        assertEquals("https://x.com/mcp", config.getMcpServers().get("b").url());
    }

    @Test
    void unknownKeyStillRejected() throws IOException {
        write(globalFile(), """
                mcp_servers:
                  a:
                    type: stdio
                    command: npx
                bogus_key: 1
                """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(globalFile(), projectDir()));
    }

    @Test
    void projectOverridesSameNameServer() throws IOException {
        write(globalFile(), """
                mcp_servers:
                  a:
                    type: stdio
                    command: npx
                """);
        write(projectDir().resolve(".acode/config.yaml"), """
                mcp_servers:
                  a:
                    type: http
                    url: https://project.example.com/mcp
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals(1, config.getMcpServers().size(), "同名应整项覆盖而非合并字段");
        assertEquals("https://project.example.com/mcp", config.getMcpServers().get("a").url());
    }

    @Test
    void differentNamesAppend() throws IOException {
        write(globalFile(), """
                mcp_servers:
                  a:
                    type: stdio
                    command: npx
                """);
        write(projectDir().resolve(".acode/config.yaml"), """
                mcp_servers:
                  b:
                    type: http
                    url: https://x.com/mcp
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals(2, config.getMcpServers().size(), "不同名应追加");
        assertTrue(config.getMcpServers().containsKey("a"));
        assertTrue(config.getMcpServers().containsKey("b"));
    }

    @Test
    void mcpServersNotAMappingThrows() throws IOException {
        write(globalFile(), """
                mcp_servers: hello
                """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(globalFile(), projectDir()));
    }

    @Test
    void validatorUnaffectedByMcpServers() throws IOException {
        write(globalFile(), """
                protocol: anthropic
                model: claude-sonnet-4-6
                base_url: https://api.anthropic.com
                api_key: k
                mcp_servers:
                  a:
                    type: stdio
                    command: npx
                """);
        AppConfig config = ConfigLoader.load(globalFile(), projectDir());
        assertEquals("anthropic", config.getProtocol());
        assertEquals(1, config.getMcpServers().size());
    }
}
