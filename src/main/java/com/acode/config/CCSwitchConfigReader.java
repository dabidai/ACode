package com.acode.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CCSwitchConfigReader {

    private static final String SETTINGS_FILE = ".claude/settings.json";
    private static final String BASE_URL_KEY = "ANTHROPIC_BASE_URL";
    private static final String AUTH_TOKEN_KEY = "ANTHROPIC_AUTH_TOKEN";
    private static final String DEFAULT_AUTH_TOKEN = "PROXY_MANAGED";
    private static final Pattern MODEL_KEY_PATTERN = Pattern.compile("ANTHROPIC_DEFAULT_(\\w+)_MODEL");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Optional<CCSwitchConfig> read() {
        Path path = Path.of(System.getProperty("user.home"), SETTINGS_FILE);
        return read(path);
    }

    public static Optional<CCSwitchConfig> read(Path settingsFile) {
        if (!Files.exists(settingsFile)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(settingsFile.toFile());
            return parse(root);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<CCSwitchConfig> parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode env = root.get("env");
        if (env == null || !env.isObject()) {
            return Optional.empty();
        }

        JsonNode baseUrlNode = env.get(BASE_URL_KEY);
        if (baseUrlNode == null || !baseUrlNode.isTextual() || baseUrlNode.asText().isBlank()) {
            return Optional.empty();
        }
        String baseUrl = baseUrlNode.asText();

        String apiKey = DEFAULT_AUTH_TOKEN;
        JsonNode authTokenNode = env.get(AUTH_TOKEN_KEY);
        if (authTokenNode != null && authTokenNode.isTextual()) {
            apiKey = authTokenNode.asText();
        }

        String model = null;
        JsonNode modelNode = root.get("model");
        if (modelNode != null && modelNode.isTextual() && !modelNode.asText().isBlank()) {
            model = modelNode.asText();
        }

        Map<String, String> modelMapping = buildModelMapping(env);
        return Optional.of(new CCSwitchConfig(baseUrl, apiKey, model, modelMapping));
    }

    private static Map<String, String> buildModelMapping(JsonNode env) {
        Map<String, String> mapping = new LinkedHashMap<>();
        env.fieldNames().forEachRemaining(fieldName -> {
            Matcher matcher = MODEL_KEY_PATTERN.matcher(fieldName);
            if (matcher.matches()) {
                String tier = matcher.group(1);
                String nameKey = "ANTHROPIC_DEFAULT_" + tier + "_MODEL_NAME";
                JsonNode modelNode = env.get(fieldName);
                JsonNode nameNode = env.get(nameKey);
                if (modelNode != null && modelNode.isTextual()
                        && nameNode != null && nameNode.isTextual()) {
                    String claudeModel = modelNode.asText();
                    String actualModel = nameNode.asText();
                    mapping.put(claudeModel, actualModel);
                    mapping.put(tier.toLowerCase(), actualModel);
                }
            }
        });
        return mapping;
    }
}
