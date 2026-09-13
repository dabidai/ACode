package com.acode.command;

import java.util.Locale;

/**
 * 命令解析：纯静态、无状态、无终端依赖。
 * 按第一个空白字符切分命令名与参数原文；参数含内部空白与换行、原样保留，不做引号解析与多参数结构化。
 * 命令名统一小写化（参数不受影响）；命令名后只有空白视为无参数；斜杠后没有命令名标记为"仅斜杠"。
 */
public final class CommandParser {

    /** 解析结果：command 是否为斜杠输入；slashOnly 为"仅斜杠"（供调度器走帮助分支）；
     * name 为小写化的命令名；args 为参数原文（无参数时为 null）。 */
    public record Parsed(boolean command, boolean slashOnly, String name, String args) {

        /** 非斜杠开头输入的常量结果 */
        public static final Parsed NOT_COMMAND = new Parsed(false, false, null, null);
    }

    private CommandParser() {
    }

    public static Parsed parse(String input) {
        if (input == null || input.isEmpty() || input.charAt(0) != '/') {
            return Parsed.NOT_COMMAND;
        }
        int firstWs = firstWhitespace(input);
        String name = input.substring(1, firstWs).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return new Parsed(true, true, null, null);
        }
        String args = (firstWs < input.length()) ? input.substring(firstWs + 1) : null;
        if (args != null && args.isBlank()) {
            args = null;
        }
        return new Parsed(true, false, name, args);
    }

    /** 从下标 1 起找第一个空白字符；不存在时返回 input.length() */
    private static int firstWhitespace(String input) {
        for (int i = 1; i < input.length(); i++) {
            if (Character.isWhitespace(input.charAt(i))) {
                return i;
            }
        }
        return input.length();
    }
}
