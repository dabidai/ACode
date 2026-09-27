package com.acode.subagent;

import com.acode.permission.PermissionMode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class AgentDefinitionParser {
    private static final Pattern HEADER = Pattern.compile("\\A(?:\\uFEFF)?---[ \\t]*\\R(.*?)\\R---[ \\t]*(?:\\R|$)", Pattern.DOTALL);
    private static final Set<String> KEYS = Set.of("name", "description", "tools", "disallowedTools", "model", "maxTurns", "permissionMode");

    public AgentDefinition parse(String text, String path, String source, Consumer<String> warnings) {
        var match = HEADER.matcher(text);
        if (!match.find()) throw new IllegalArgumentException("缺少 frontmatter（name/description）");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options)).load(match.group(1));
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("frontmatter 必须是映射");
        for (Object key : map.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("未知字段 " + key);
        String name = required(map, "name"), description = required(map, "description");
        String model = "inherit";
        if (map.containsKey("model")) {
            if (map.get("model") instanceof String value && Set.of("inherit", "sonnet", "opus", "haiku").contains(value)) model = value;
            else warnings.accept("Agent 定义 " + path + "：model 取值非法（" + map.get("model") + "），按 inherit 处理");
        }
        int turns = 20;
        if (map.containsKey("maxTurns")) {
            Object value = map.get("maxTurns");
            if (value instanceof Integer n && n > 0) turns = n;
            else warnings.accept("Agent 定义 " + path + "：maxTurns 取值非法（" + value + "），按默认值 20 处理");
        }
        PermissionMode mode = PermissionMode.DEFAULT;
        if (map.containsKey("permissionMode")) {
            var candidate = PermissionMode.fromConfig(String.valueOf(map.get("permissionMode")));
            if (candidate != null) mode = candidate;
            else warnings.accept("Agent 定义 " + path + "：permissionMode 取值非法（" + map.get("permissionMode") + "），按 default 处理");
        }
        return new AgentDefinition(name, description, names(map, "tools"), names(map, "disallowedTools"),
                model, turns, mode, text.substring(match.end()), path, source);
    }
    private static String required(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("缺少 " + key);
        return value;
    }
    private static List<String> names(Map<?, ?> map, String key) {
        if (!map.containsKey(key)) return List.of();
        if (!(map.get(key) instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank()))
            throw new IllegalArgumentException(key + " 必须是工具名数组");
        return values.stream().map(String.class::cast).toList();
    }
}
