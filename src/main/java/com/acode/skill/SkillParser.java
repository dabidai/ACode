package com.acode.skill;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class SkillParser {
    private static final Pattern HEADER = Pattern.compile("\\A(?:\\uFEFF)?---\\s*\\R(.*?)\\R---[ \\t]*(?:\\R|$)", Pattern.DOTALL);

    public SkillDefinition parse(String text, String expectedName, SkillSource source) {
        try {
            var match = HEADER.matcher(text);
            if (!match.find()) throw new IllegalArgumentException("frontmatter: name/description required");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object parsed = new Yaml(new SafeConstructor(options)).load(match.group(1));
            if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("frontmatter must be a map");
            String name = required(map, "name");
            if (!name.matches("[a-z0-9-]+") || !name.equals(expectedName))
                throw new IllegalArgumentException("name must match file/directory: " + expectedName);
            String description = required(map, "description");
            Object raw = map.containsKey("allowedTools") ? map.get("allowedTools") : List.of();
            if (!(raw instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank()))
                throw new IllegalArgumentException("allowedTools must be a list of tool names");
            List<String> allowed = ((List<?>) raw).stream().map(String.class::cast).toList();
            String model = map.containsKey("model") ? required(map, "model") : null;
            String mode = enumeration(map, "mode", "inline", List.of("inline", "fork"));
            String context = enumeration(map, "context", "full", List.of("full", "recent", "none"));
            String body = text.substring(match.end());
            if (body.indexOf("$ARGUMENTS") != body.lastIndexOf("$ARGUMENTS"))
                throw new IllegalArgumentException("$ARGUMENTS may occur at most once");
            return new SkillDefinition(name, description, allowed, model, mode, context, body, source);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(source.location() + ": " + e.getMessage(), e);
        }
    }
    private static String required(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException(key + " must be a non-empty string");
        return value;
    }
    private static String enumeration(Map<?, ?> map, String key, String fallback, List<String> values) {
        String value = map.containsKey(key) ? required(map, key) : fallback;
        if (!values.contains(value)) throw new IllegalArgumentException("invalid " + key + ": " + value);
        return value;
    }
}
