package com.acode.ui;

import org.jline.utils.InfoCmp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TerminalCapsTest {

    /** JLine 自带的 Windows 能力表原文，也是 AcodeTerminal 在 Windows 上修补的输入。 */
    private static final String CAPS_RESOURCE = "/org/jline/utils/windows-vtp.caps";

    /** 读取 classpath 资源为 UTF-8 字符串；读不到直接 fail，不静默跳过。 */
    private static String readCapsResource(String path) {
        try (InputStream in = TerminalCaps.class.getResourceAsStream(path)) {
            if (in == null) {
                fail("caps 资源读取失败：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("caps 资源读取失败：" + path + "：" + e.getMessage());
            return null;
        }
    }

    @Test
    void removesAmContentFromRealWindowsVtpCapsPreservingAllOtherBytes() {
        String base = readCapsResource(CAPS_RESOURCE);
        String result = TerminalCaps.withoutAutoRightMargin(base);

        // 只摘掉 am 两个字符：总长度差恰好 2
        assertEquals(2, base.length() - result.length(), "去掉 am 后长度应恰好少 2");

        // 行数不变
        String[] baseLines = base.split("\n", -1);
        String[] resultLines = result.split("\n", -1);
        assertEquals(baseLines.length, resultLines.length, "行数不应变化");

        // 前置：资源文件第 2 行内容（该文件里 am 只出现这一次）
        assertEquals("\tam, mc5i, mir, msgr,", baseLines[1], "前置：资源文件第 2 行内容");

        // 逐行比较：只有第 2 行（索引 1）不同，其余逐字节相同
        int diffLine = -1;
        for (int i = 0; i < baseLines.length; i++) {
            if (baseLines[i].equals(resultLines[i])) {
                continue;
            }
            if (diffLine >= 0) {
                fail("预期只有一行不同，但第 " + diffLine + " 行与第 " + i + " 行都不同");
            }
            diffLine = i;
        }
        assertEquals(1, diffLine, "应只有第 2 行（索引 1）不同");

        // 不同的那行等于原行删掉 am 两个字符，前后空白与逗号全部保留
        assertEquals(baseLines[1].replaceFirst("am", ""), resultLines[1],
                "第 2 行应只删掉 am 内容、保留空白与逗号");
        assertEquals("\t, mc5i, mir, msgr,", resultLines[1], "第 2 行的精确期望");

        // 同行的其余能力必须仍在结果里
        assertTrue(result.contains("mc5i"), "mc5i 条目必须保留");
        assertTrue(result.contains("mir"), "mir 条目必须保留");
        assertTrue(result.contains("msgr"), "msgr 条目必须保留");
    }

    @Test
    void returnsInputUnchangedWhenNoAmEntry() {
        String input = "acode-test|test caps, sam, msgr, amx=\\E[1m, cols#80, it#8, pairs#64,"
                + " smcup=\\E[?1049h, rmcup=\\E[?1049l, xenl, bce,";
        String result = TerminalCaps.withoutAutoRightMargin(input);

        // 不含 am 时逐字节原样返回（幂等）
        assertEquals(input, result, "不含 am 时应逐字节原样返回");
        // 前缀/后缀含 am 的条目不得被误删
        assertTrue(result.contains("sam"), "sam 条目不得被误删");
        assertTrue(result.contains("msgr"), "msgr 条目不得被误删");
        assertTrue(result.contains("amx=\\E[1m"), "amx=... 条目不得被误删");
    }

    @Test
    void returnsNullOrEmptyInputUnchanged() {
        assertNull(TerminalCaps.withoutAutoRightMargin(null), "null 应原样返回");
        assertEquals("", TerminalCaps.withoutAutoRightMargin(""), "空串应原样返回");
    }

    @Test
    void isWindowsDetectsOnlyWindowsOsNames() {
        assertTrue(TerminalCaps.isWindows("Windows 11"), "Windows 11 应判为 Windows");
        assertFalse(TerminalCaps.isWindows("Linux"), "Linux 不应判为 Windows");
        assertFalse(TerminalCaps.isWindows(null), "null 不应判为 Windows");
    }

    @Test
    void blankTermStillSelectsBundledWindowsCapabilities() {
        assertTrue(TerminalCaps.shouldUseWindowsType("Windows 11", " ", null));
        assertTrue(TerminalCaps.shouldUseWindowsType("Windows 11", "", ""));
        assertFalse(TerminalCaps.shouldUseWindowsType("Windows 11", "dumb", null));
        assertFalse(TerminalCaps.shouldUseWindowsType("Linux", "", null));
    }

    @Test
    void customTypeRegistersIntoJLineInfoCmp() {
        assertEquals("acode-vtp", TerminalCaps.customType(), "customType() 应为 acode-vtp");

        String caps = "acode-vtp|acode test terminal,\n"
                + "\tmc5i, cols#120, lines#40, pairs#64,\n"
                + "\tbel=^G, bold=\\E[1m,\n";
        InfoCmp.setLoadedInfoCmp(TerminalCaps.customType(), caps);
        assertEquals(caps, InfoCmp.getLoadedInfoCmp(TerminalCaps.customType()),
                "用 customType() 注册的能力表应原样读回");
    }

    @Test
    void builtInTerminfoNamesCannotBeOverridden() {
        InfoCmp.setDefaultInfoCmp("ansi", "xxx-should-not-win");
        assertNotEquals("xxx-should-not-win", InfoCmp.getLoadedInfoCmp("ansi"),
                "JLine 内置名 ansi 不允许被覆盖");
    }
}
