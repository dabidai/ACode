package com.acode.testutil;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 断言辅助：命令输出上色之后，对「可见文本」的断言得先去掉 ANSI 序列，
 * 否则「标签 + 值」这类连续子串（如 {@code 模式：default}）会因为中间夹了转义码而匹配不上，
 * 而按前缀找行的结构性判断（如 {@code startsWith("  /")}）也会全部落空。
 * <p>用法是「先去色、再断言原来那个期望串」——期望值本身不变，断言的强度也不变。
 */
public final class AnsiTestSupport {

    /** CSI 序列：ESC [ 参数 终止字母（SGR 上色与光标移动都涵盖） */
    private static final Pattern CSI = Pattern.compile("\\x1B\\[[0-9;]*[A-Za-z]");

    private AnsiTestSupport() {
    }

    /** 去掉全部 ANSI 转义序列，得到人眼看到的文本。 */
    public static String stripAnsi(String text) {
        return text == null ? null : CSI.matcher(text).replaceAll("");
    }

    /** 逐行去 ANSI。 */
    public static List<String> stripAnsi(List<String> lines) {
        return lines.stream().map(AnsiTestSupport::stripAnsi).toList();
    }
}
