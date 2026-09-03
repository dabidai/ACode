package com.acode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CCSwitchConfigReaderTest {

    @TempDir
    Path tempDir;

    private Path settingsFile() {
        return tempDir.resolve("settings.json");
    }

    private void write(String content) throws IOException {
        Files.writeString(settingsFile(), content);
    }

    @Test
    void fileNotExistsReturnsEmpty() {
        Optional<CCSwitchConfig> result = CCSwitchConfigReader.read(tempDir.resolve("nonexistent.json"));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyJsonReturnsEmpty() throws IOException {
        write("{}");
        assertTrue(CCSwitchConfigReader.read(settingsFile()).isEmpty());
    }

    @Test
    void noEnvFieldReturnsEmpty() throws IOException {
        write("""
                {"model": "opus"}
                """);
        assertTrue(CCSwitchConfigReader.read(settingsFile()).isEmpty());
    }

    @Test
    void envWithoutBaseUrlReturnsEmpty() throws IOException {
        write("""
                {"env": {"ANTHROPIC_AUTH_TOKEN": "test-token"}}
                """);
        assertTrue(CCSwitchConfigReader.read(settingsFile()).isEmpty());
    }

    @Test
    void blankBaseUrlReturnsEmpty() throws IOException {
        write("""
                {"env": {"ANTHROPIC_BASE_URL": "  "}}
                """);
        assertTrue(CCSwitchConfigReader.read(settingsFile()).isEmpty());
    }

    @Test
    void invalidJsonReturnsEmpty() throws IOException {
        write("not json at all {{{");
        assertTrue(CCSwitchConfigReader.read(settingsFile()).isEmpty());
    }

    @Test
    void validConfigExtractedCorrectly() throws IOException {
        write("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_AUTH_TOKEN": "PROXY_MANAGED"
                  }
                }
                """);
        Optional<CCSwitchConfig> result = CCSwitchConfigReader.read(settingsFile());
        assertTrue(result.isPresent());
        assertEquals("http://127.0.0.1:15721", result.get().baseUrl());
        assertEquals("PROXY_MANAGED", result.get().apiKey());
        assertTrue(result.get().modelMapping().isEmpty());
    }

    @Test
    void missingAuthTokenDefaultsToProxyManaged() throws IOException {
        write("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721"}}
                """);
        Optional<CCSwitchConfig> result = CCSwitchConfigReader.read(settingsFile());
        assertTrue(result.isPresent());
        assertEquals("PROXY_MANAGED", result.get().apiKey());
    }

    @Test
    void modelMappingExtracted() throws IOException {
        write("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_AUTH_TOKEN": "test",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": "claude-sonnet-4-6[1M]",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME": "agnes-2.0-flash",
                    "ANTHROPIC_DEFAULT_OPUS_MODEL": "claude-opus-4-8[1M]",
                    "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME": "agnes-2.0-pro"
                  }
                }
                """);
        Optional<CCSwitchConfig> result = CCSwitchConfigReader.read(settingsFile());
        assertTrue(result.isPresent());
        var mapping = result.get().modelMapping();
        assertEquals(4, mapping.size());
        assertEquals("agnes-2.0-flash", mapping.get("claude-sonnet-4-6[1M]"));
        assertEquals("agnes-2.0-pro", mapping.get("claude-opus-4-8[1M]"));
        assertEquals("agnes-2.0-flash", mapping.get("sonnet"));
        assertEquals("agnes-2.0-pro", mapping.get("opus"));
    }

    @Test
    void modelWithoutNameNotInMapping() throws IOException {
        write("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": "claude-sonnet-4-6[1M]"
                  }
                }
                """);
        Optional<CCSwitchConfig> result = CCSwitchConfigReader.read(settingsFile());
        assertTrue(result.isPresent());
        assertTrue(result.get().modelMapping().isEmpty());
    }

    @Test
    void modelMappingIsImmutable() throws IOException {
        write("""
                {
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:15721",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": "claude-sonnet-4-6[1M]",
                    "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME": "agnes-2.0-flash"
                  }
                }
                """);
        CCSwitchConfig config = CCSwitchConfigReader.read(settingsFile()).orElseThrow();
        assertThrows(UnsupportedOperationException.class,
                () -> config.modelMapping().put("new-key", "new-value"));
    }

    @Test
    void topLevelModelFieldExtracted() throws IOException {
        write("""
                {
                  "env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721"},
                  "model": "opus"
                }
                """);
        CCSwitchConfig config = CCSwitchConfigReader.read(settingsFile()).orElseThrow();
        assertEquals("opus", config.model());
    }

    @Test
    void missingTopLevelModelFieldIsNull() throws IOException {
        write("""
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721"}}
                """);
        CCSwitchConfig config = CCSwitchConfigReader.read(settingsFile()).orElseThrow();
        assertNull(config.model());
    }
}
