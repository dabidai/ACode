package com.acode.hook;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class HookLoader {
    public record LoadResult(List<HookConfig> hooks, List<String> errors) {
        public LoadResult { hooks = List.copyOf(hooks); errors = List.copyOf(errors); }
    }
    public static LoadResult load(Path project, Path home) {
        List<HookConfig> hooks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        loadFile(project.resolve(".acode/hooks.local.yaml"), "local", hooks, errors, ids);
        loadFile(project.resolve(".acode/hooks.yaml"), "project", hooks, errors, ids);
        loadFile(home.resolve(".acode/hooks.yaml"), "user", hooks, errors, ids);
        return new LoadResult(hooks, errors);
    }
    private static void loadFile(Path file, String source, List<HookConfig> hooks, List<String> errors, Set<String> ids) {
        if (!Files.exists(file)) return;
        Object root;
        try (var reader = Files.newBufferedReader(file)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (Exception e) {
            LoggerFactory.getLogger(HookLoader.class).warn("Hook 文件跳过 {}：{}", file, e.getMessage());
            return;
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("hooks") instanceof List<?> list)) return;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) continue;
            String id = source + ":.acode/" + file.getFileName() + "#" + (i + 1);
            try {
                if (entry.containsKey("id")) id = required(entry, "id");
                unknown(entry, Set.of("id", "event", "if", "action", "reject", "once", "async"), "顶层");
                String event = String.valueOf(entry.get("event"));
                if (!HookEvents.ALL.contains(event)) throw new IllegalArgumentException("事件名不合法：" + event + "（合法值：" + HookEvents.NAMES + "）");
                boolean reject = bool(entry, "reject"), once = bool(entry, "once"), async = bool(entry, "async");
                if (reject && !event.equals(HookEvents.PRE_TOOL_USE)) throw new IllegalArgumentException("reject 只能用在 " + HookEvents.PRE_TOOL_USE + " 事件上");
                if (async && event.equals(HookEvents.PRE_TOOL_USE)) throw new IllegalArgumentException("async 不能用在 " + HookEvents.PRE_TOOL_USE + " 事件上");
                if (!(entry.get("action") instanceof Map<?, ?> action)) throw new IllegalArgumentException("动作缺少必填字段：action");
                unknown(action, Set.of("type", "command", "timeout", "message", "url", "method", "body", "prompt"), "action");
                String type = String.valueOf(action.get("type"));
                if (!Set.of("command", "prompt", "http", "agent").contains(type))
                    throw new IllegalArgumentException("动作类型不合法：" + type + "（合法值：command / prompt / http / agent）");
                String field = switch (type) { case "command" -> "command"; case "prompt" -> "message"; case "http" -> "url"; default -> "prompt"; };
                required(action, field);
                Map<String, String> values = new HashMap<>();
                for (var kv : action.entrySet()) {
                    if (kv.getKey().equals("timeout")) continue;
                    if (!(kv.getValue() instanceof String)) throw new IllegalArgumentException("action 字段需要字符串：" + kv.getKey());
                    values.put(String.valueOf(kv.getKey()), (String) kv.getValue());
                }
                if (type.equals("http") && !Set.of("GET", "POST", "PUT", "DELETE").contains(values.getOrDefault("method", "POST")))
                    throw new IllegalArgumentException("HTTP method 不合法：" + values.get("method"));
                Duration timeout = duration(action.getOrDefault("timeout", null), type.equals("http") ? 10_000 : 30_000);
                HookCondition condition = entry.containsKey("if") ? HookCondition.parse(entry.get("if")) : c -> true;
                if (!ids.add(id)) throw new IllegalArgumentException("id 重复：" + id);
                hooks.add(new HookConfig(id, event, condition,
                        new HookConfig.Action(HookConfig.ActionType.valueOf(type.toUpperCase(Locale.ROOT)), values, timeout), reject, once, async));
            } catch (IllegalArgumentException e) {
                errors.add(file + ": 第 " + (i + 1) + " 条 Hook（id=" + id + "）：" + e.getMessage());
            }
        }
    }
    private static void unknown(Map<?, ?> map, Set<String> keys, String where) {
        for (Object key : map.keySet()) if (!keys.contains(String.valueOf(key))) throw new IllegalArgumentException(where + (where.equals("顶层") ? "" : " ") + "含未知字段：" + key);
    }
    private static String required(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException("动作缺少必填字段：" + key);
        return s;
    }
    private static boolean bool(Map<?, ?> map, String key) {
        if (!map.containsKey(key)) return false;
        if (!(map.get(key) instanceof Boolean b)) throw new IllegalArgumentException(key + " 必须是布尔值");
        return b;
    }
    private static Duration duration(Object raw, long fallback) {
        if (raw == null) return Duration.ofMillis(fallback);
        var matcher = Pattern.compile("([1-9][0-9]*)(ms|s|m)?").matcher(String.valueOf(raw));
        try {
            if (matcher.matches()) {
                long factor = "ms".equals(matcher.group(2)) ? 1 : "m".equals(matcher.group(2)) ? 60_000 : 1000;
                return Duration.ofMillis(Math.multiplyExact(Long.parseLong(matcher.group(1)), factor));
            }
        } catch (ArithmeticException | NumberFormatException ignored) { }
        throw new IllegalArgumentException("timeout 格式不合法：" + raw + "（应为 <正整数>ms|s|m）");
    }
}
