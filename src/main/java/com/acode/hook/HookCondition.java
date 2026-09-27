package com.acode.hook;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@FunctionalInterface
public interface HookCondition {
    boolean matches(HookContext context);

    record All(List<HookCondition> children) implements HookCondition {
        public All { children = List.copyOf(children); }
        public boolean matches(HookContext c) { return children.stream().allMatch(it -> it.matches(c)); }
    }
    record Any(List<HookCondition> children) implements HookCondition {
        public Any { children = List.copyOf(children); }
        public boolean matches(HookContext c) { return children.stream().anyMatch(it -> it.matches(c)); }
    }
    record Not(HookCondition child) implements HookCondition {
        public boolean matches(HookContext c) { return !child.matches(c); }
    }
    record MatchSpec(String field, String exact, Pattern pattern, boolean glob) implements HookCondition {
        public boolean matches(HookContext c) {
            String value = c.field(field);
            if (value == null) return false;
            if (pattern == null) {
                if (field.startsWith("args.") && c.args().get(field.substring(5)).isContainerNode()) return false;
                return value.equals(exact);
            }
            return glob ? pattern.matcher(value.replace('\\', '/')).matches() : pattern.matcher(value).find();
        }
    }
    static HookCondition parse(Object raw) {
        return parse(raw, 0);
    }
    private static HookCondition parse(Object raw, int depth) {
        if (depth > 64) throw new IllegalArgumentException("条件结构不合法：嵌套超过 64 层或存在循环引用");
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("条件结构不合法：需要映射");
        List<HookCondition> parts = new ArrayList<>();
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (key.equals("all") || key.equals("any")) {
                if (!(value instanceof List<?> list)) throw new IllegalArgumentException("条件结构不合法：" + key + " 需要列表");
                List<HookCondition> children = list.stream().map(child -> parse(child, depth + 1)).toList();
                parts.add(key.equals("all") ? new All(children) : new Any(children));
            } else if (key.equals("not")) {
                parts.add(new Not(parse(value, depth + 1)));
            } else {
                if (!key.equals("tool") && !(key.startsWith("args.") && key.length() > 5))
                    throw new IllegalArgumentException("条件含未知字段：" + key);
                if (value instanceof Map<?, ?> matcher) {
                    if (matcher.size() != 1 || !(matcher.containsKey("regex") || matcher.containsKey("glob")))
                        throw new IllegalArgumentException("条件结构不合法：匹配器需要 regex 或 glob");
                    boolean glob = matcher.containsKey("glob");
                    Object expression = matcher.get(glob ? "glob" : "regex");
                    if (!(expression instanceof String text)) throw new IllegalArgumentException("条件结构不合法：匹配器需要字符串");
                    try { parts.add(new MatchSpec(key, null, Pattern.compile(glob ? globRegex(text) : text), glob)); }
                    catch (PatternSyntaxException e) { throw new IllegalArgumentException("正则无法编译：" + text); }
                } else {
                    if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
                        throw new IllegalArgumentException("条件结构不合法：精确值需要标量");
                    parts.add(new MatchSpec(key, String.valueOf(value), null, false));
                }
            }
        }
        return new All(parts);
    }
    private static String globRegex(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '*') { out.append(".*"); i++; }
                else out.append("[^/]*");
            } else if (c == '?') out.append("[^/]");
            else out.append(Pattern.quote(String.valueOf(c)));
        }
        return out.toString();
    }
}
