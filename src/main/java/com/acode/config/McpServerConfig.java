package com.acode.config;

import com.acode.tool.Permission;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 单个 MCP server 的不可变配置模型。由 {@link #fromYaml} 集中解析并校验
 * （必填字段、类型、枚举值、超时正整数、name 字符集），错误消息带
 * {@code mcp_servers.<name>} 定位。超时默认 60 秒、权限档默认 exec（最严档）、开关默认开。
 */
public final class McpServerConfig {

    public enum Type { STDIO, HTTP }

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final Set<String> PERMISSION_NAMES = Set.of("read", "write", "exec");
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final String name;
    private final Type type;
    private final List<String> command;
    private final Map<String, String> env;
    private final String url;
    private final Map<String, String> headers;
    private final long timeoutSeconds;
    private final Permission permission;
    private final boolean enabled;

    private McpServerConfig(String name, Type type, List<String> command, Map<String, String> env,
                            String url, Map<String, String> headers, long timeoutSeconds,
                            Permission permission, boolean enabled) {
        this.name = name;
        this.type = type;
        this.command = command;
        this.env = env;
        this.url = url;
        this.headers = headers;
        this.timeoutSeconds = timeoutSeconds;
        this.permission = permission;
        this.enabled = enabled;
    }

    /** 集中解析嵌套结构并校验；yaml 为 mcp_servers.<name> 下的映射。 */
    public static McpServerConfig fromYaml(String name, Map<?, ?> yaml, String source) {
        String where = source + ": mcp_servers." + name;
        validateName(name, where);
        if (yaml == null) {
            throw new ConfigException(where + " 必须是映射");
        }
        Type type = parseType(yaml.get("type"), where);
        long timeout = parseTimeout(yaml.get("timeout"), where);
        Permission permission = parsePermission(yaml.get("permission"), where);
        boolean enabled = parseEnabled(yaml.get("enabled"), where);
        if (type == Type.STDIO) {
            String command = requireString(yaml.get("command"), "command", where);
            List<String> args = parseStringList(yaml.get("args"), "args", where);
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(command);
            if (args != null) {
                fullCommand.addAll(args);
            }
            return new McpServerConfig(name, type, fullCommand,
                    parseStringMap(yaml.get("env"), "env", where),
                    null, Map.of(), timeout, permission, enabled);
        }
        String url = requireString(yaml.get("url"), "url", where);
        validateUrl(url, where);
        return new McpServerConfig(name, type, List.of(), Map.of(), url,
                parseStringMap(yaml.get("headers"), "headers", where),
                timeout, permission, enabled);
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    /** stdio 型：命令 + 参数完整列表；http 型为空 */
    public List<String> command() {
        return command;
    }

    /** stdio 型：显式环境变量覆盖 */
    public Map<String, String> env() {
        return env;
    }

    /** http 型：端点 URL；stdio 型为 null */
    public String url() {
        return url;
    }

    /** http 型：静态请求头 */
    public Map<String, String> headers() {
        return headers;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 权限档：未声明默认 exec（最严档） */
    public Permission permission() {
        return permission;
    }

    public boolean enabled() {
        return enabled;
    }

    private static void validateName(String name, String where) {
        if (name == null || name.isBlank() || !NAME_PATTERN.matcher(name).matches()) {
            throw new ConfigException(where + " 名字非法（只允许字母/数字/下划线/连字符）：" + name);
        }
    }

    private static Type parseType(Object value, String where) {
        if (value == null) {
            throw new ConfigException(where + " 缺少必填项 type（stdio/http）");
        }
        if ("stdio".equals(value)) {
            return Type.STDIO;
        }
        if ("http".equals(value)) {
            return Type.HTTP;
        }
        throw new ConfigException(where + " type 必须是 stdio/http，当前值 " + value);
    }

    private static String requireString(Object value, String field, String where) {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ConfigException(where + " 缺少必填项 " + field + "（字符串）");
        }
        return string;
    }

    private static List<String> parseStringList(Object value, String field, String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new ConfigException(where + " 的 " + field + " 必须是字符串列表");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string)) {
                throw new ConfigException(where + " 的 " + field + " 元素必须是字符串");
            }
            result.add(string);
        }
        return result;
    }

    private static Map<String, String> parseStringMap(Object value, String field, String where) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigException(where + " 的 " + field + " 必须是字符串映射");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String stringValue)) {
                throw new ConfigException(where + " 的 " + field + " 键值都必须是字符串");
            }
            result.put(key, stringValue);
        }
        return result;
    }

    private static long parseTimeout(Object value, String where) {
        if (value == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        if (!(value instanceof Number number) || number.longValue() <= 0) {
            throw new ConfigException(where + " 的 timeout 必须是正整数（秒），当前值 " + value);
        }
        return number.longValue();
    }

    private static Permission parsePermission(Object value, String where) {
        if (value == null) {
            return Permission.EXEC;
        }
        if (!(value instanceof String string) || !PERMISSION_NAMES.contains(string)) {
            throw new ConfigException(where + " 的 permission 必须是 read/write/exec 之一，当前值 " + value);
        }
        return switch (string) {
            case "read" -> Permission.READ;
            case "write" -> Permission.WRITE;
            default -> Permission.EXEC;
        };
    }

    private static boolean parseEnabled(Object value, String where) {
        if (value == null) {
            return true;
        }
        if (!(value instanceof Boolean enabled)) {
            throw new ConfigException(where + " 的 enabled 必须是 true/false，当前值 " + value);
        }
        return enabled;
    }

    private static void validateUrl(String url, String where) {
        try {
            URI uri = URI.create(url);
            if (!uri.isAbsolute() || !Set.of("http", "https").contains(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + " 的 url 不是合法 http(s) URL：" + url);
        }
    }
}
