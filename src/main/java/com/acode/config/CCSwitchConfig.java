package com.acode.config;

import java.util.Map;

public record CCSwitchConfig(String baseUrl, String apiKey, String model, Map<String, String> modelMapping) {

    public CCSwitchConfig {
        modelMapping = modelMapping == null ? Map.of() : Map.copyOf(modelMapping);
    }
}
