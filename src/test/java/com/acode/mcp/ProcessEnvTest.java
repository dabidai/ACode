package com.acode.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProcessEnv 纯单测：不起子进程，直接断言 build() 输出的白名单/清除/覆盖契约。
 * 平台通过 osName 参数注入，可覆盖 Windows 与非 Windows 两个分支。
 */
class ProcessEnvTest {

    @Test
    void windowsWhitelistKeepsRequiredKeysAndDropsUnlisted() {
        Map<String, String> env = ProcessEnv.build("Windows 11", Map.of());
        assertTrue(env.containsKey("PATH"), "PATH 应在白名单内");
        assertTrue(env.containsKey("SystemRoot"));
        assertTrue(env.containsKey("ComSpec"));
        assertTrue(env.containsKey("USERPROFILE"));
        assertFalse(env.containsKey("API_KEY"), "未声明变量应被清掉");
        assertFalse(env.containsKey("ACODE_TEST_TOKEN"), "未声明变量应被清掉");
    }

    @Test
    void unixWhitelistKeepsRequiredKeysAndDropsUnlisted() {
        Map<String, String> env = ProcessEnv.build("Linux", Map.of());
        assertTrue(env.containsKey("PATH"), "PATH 应在白名单内");
        assertTrue(env.containsKey("HOME"));
        assertFalse(env.containsKey("API_KEY"), "未声明变量应被清掉");
    }

    @Test
    void explicitEnvOverridesSameName() {
        Map<String, String> env = ProcessEnv.build("Windows 11", Map.of("PATH", "/custom"));
        assertEquals("/custom", env.get("PATH"), "显式 env 应覆盖同名白名单项");
    }

    @Test
    void explicitEnvAddsUnlistedKey() {
        Map<String, String> env = ProcessEnv.build("Windows 11", Map.of("ACODE_MCP_TOKEN", "abc"));
        assertEquals("abc", env.get("ACODE_MCP_TOKEN"), "显式 env 应新增未列出的变量");
    }

    @Test
    void missingWhitelistKeysAreSkippedWithoutError() {
        // Linux 白名单含 LANG/TMPDIR，Windows 宿主机上这些变量通常不存在 → 跳过不报错、不放入 null
        Map<String, String> env = ProcessEnv.build("Linux", Map.of());
        assertTrue(env.values().stream().noneMatch(v -> v == null), "白名单项缺失时应跳过，不产生 null 值");
        assertNotNull(env.get("PATH"));
    }
}
