package com.acode.permission;

import java.util.regex.Pattern;

/**
 * 权限规则：工具名(模式) + 效果。
 * 匹配语义：先精确相等（命中「始终允许」持久化的精确内容），再扁平 glob 全串匹配。
 * glob 中裸 {@code *} → {@code .*}、裸 {@code ?} → {@code .}（可跨 /）；
 * 反斜杠转义的 {@code \*}、{@code \?} 按字面处理（持久化时转义，保证持久化内容不被通配误匹配）。
 */
public record PermissionRule(String toolName, String pattern, RuleEffect effect) {

    public enum RuleEffect { ALLOW, DENY }

    public boolean matches(String toolName, String content) {
        if (!this.toolName.equals(toolName) || content == null) {
            return false;
        }
        if (pattern.equals(content)) {
            return true;
        }
        return globRegex().matcher(content).matches();
    }

    private Pattern globRegex() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                char next = pattern.charAt(i + 1);
                if (next == '*' || next == '?' || next == '\\') {
                    sb.append(Pattern.quote(String.valueOf(next)));
                    i += 2;
                    continue;
                }
            }
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return Pattern.compile(sb.toString());
    }

    /** 「始终允许」持久化用：转义 glob 元字符，使持久化内容按字面精确匹配 */
    public static String escapeGlob(String content) {
        return content.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }
}
