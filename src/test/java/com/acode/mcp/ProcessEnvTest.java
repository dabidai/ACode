package com.acode.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProcessEnv 纯单测：不起子进程，直接断言 build() 输出的白名单/清除/覆盖契约。
 * 平台与父环境都通过参数注入，可覆盖 Windows 与非 Windows 两个分支，且不依赖调用方 shell 的环境。
 */
class ProcessEnvTest {

    @Test
    void windowsWhitelistKeepsRequiredKeysAndDropsUnlisted() {
        // 父环境用 Windows 实际存储的大小写（多为全大写）：白名单里 windir/SystemRoot 这类混合大小写项
        // 必须仍能取到值——System.getenv(name) 在 Windows 上不区分大小写，注入的 map 需自行兜住
        Map<String, String> parent = Map.of(
                "PATH", "C:\\Windows", "SYSTEMROOT", "C:\\Windows", "WINDIR", "C:\\Windows",
                "COMSPEC", "C:\\Windows\\system32\\cmd.exe", "USERPROFILE", "C:\\Users\\test",
                "API_KEY", "secret", "ACODE_TEST_TOKEN", "secret");
        Map<String, String> env = ProcessEnv.build("Windows 11", parent, Map.of());

        assertTrue(env.containsKey("PATH"), "PATH 应在白名单内");
        assertTrue(env.containsKey("SystemRoot"), "SYSTEMROOT 应按不区分大小写取到");
        assertTrue(env.containsKey("windir"), "WINDIR 应按不区分大小写取到");
        assertTrue(env.containsKey("ComSpec"), "COMSPEC 应按不区分大小写取到");
        assertTrue(env.containsKey("USERPROFILE"));
        assertFalse(env.containsKey("API_KEY"), "未声明变量应被清掉");
        assertFalse(env.containsKey("ACODE_TEST_TOKEN"), "未声明变量应被清掉");
    }

    @Test
    void unixLookupStaysCaseSensitive() {
        Map<String, String> parent = Map.of("PATH", "/usr/bin", "home", "/home/x");

        Map<String, String> env = ProcessEnv.build("Linux", parent, Map.of());

        assertTrue(env.containsKey("PATH"), "精确匹配即可取到");
        assertFalse(env.containsKey("HOME"), "非 Windows 不做不区分大小写匹配，home 不该顶替 HOME");
    }

    @Test
    void unixWhitelistKeepsRequiredKeysAndDropsUnlisted() {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin", "HOME", "/home/test", "API_KEY", "secret");
        Map<String, String> env = ProcessEnv.build("Linux", parent, Map.of());

        assertTrue(env.containsKey("PATH"), "PATH 应在白名单内");
        assertTrue(env.containsKey("HOME"));
        assertFalse(env.containsKey("API_KEY"), "未声明变量应被清掉");
    }

    @Test
    void explicitEnvOverridesSameName() {
        Map<String, String> env = ProcessEnv.build("Windows 11",
                Map.of("PATH", "C:\\Windows"), Map.of("PATH", "/custom"));

        assertEquals("/custom", env.get("PATH"), "显式 env 应覆盖同名白名单项");
    }

    @Test
    void explicitEnvAddsUnlistedKey() {
        Map<String, String> env = ProcessEnv.build("Windows 11", Map.of(),
                Map.of("ACODE_MCP_TOKEN", "abc"));

        assertEquals("abc", env.get("ACODE_MCP_TOKEN"), "显式 env 应新增未列出的变量");
    }

    @Test
    void missingWhitelistKeysAreSkippedWithoutError() {
        // Linux 白名单含 HOME/USER/LANG/TMPDIR，给定父环境里都没有 → 跳过不报错、不放入 null
        Map<String, String> env = ProcessEnv.build("Linux", Map.of("PATH", "/usr/bin"), Map.of());

        assertTrue(env.values().stream().noneMatch(v -> v == null), "白名单项缺失时应跳过，不产生 null 值");
        assertNotNull(env.get("PATH"));
    }
}
