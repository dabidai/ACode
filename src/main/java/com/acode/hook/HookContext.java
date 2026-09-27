package com.acode.hook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

public record HookContext(String event, String tool, JsonNode args, String message, String error) {
    private static final Pattern VARIABLE = Pattern.compile("\\$(TOOL_ARGS\\.[a-zA-Z0-9_]+|[A-Z_][A-Z0-9_]*)");
    public HookContext {
        args = args == null ? null : args.deepCopy();
    }
    public static HookContext lifecycle(String event, String message) {
        return new HookContext(event, null, null, message, null);
    }
    public String field(String key) {
        if (key.equals("tool")) return tool;
        if (!key.startsWith("args.") || args == null || !args.isObject()) return null;
        JsonNode value = args.get(key.substring(5));
        return value == null || value.isNull() ? null : value.isValueNode() ? value.asText() : value.toString();
    }
    public String expand(String template) {
        return VARIABLE.matcher(template == null ? "" : template).replaceAll(match -> {
            String key = match.group(1);
            String value = switch (key) {
                case "EVENT" -> event;
                case "TOOL_NAME" -> tool;
                case "FILE_PATH" -> filePath();
                case "MESSAGE" -> message;
                case "ERROR" -> error;
                default -> key.startsWith("TOOL_ARGS.") ? field("args." + key.substring(10)) : "";
            };
            return java.util.regex.Matcher.quoteReplacement(value == null ? "" : value);
        });
    }
    private String filePath() {
        if (args == null) return "";
        for (String key : new String[]{"file_path", "path"}) {
            JsonNode value = args.get(key);
            if (value != null && value.isTextual()) return value.asText();
        }
        return "";
    }
}
