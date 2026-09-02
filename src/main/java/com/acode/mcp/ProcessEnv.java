package com.acode.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子进程环境白名单：平台判别的最小必需环境变量集合 + 配置显式声明覆盖，其余一律不传。
 * Windows 需保留系统定位与临时目录相关变量；其他平台保留基本 shell 会话变量。
 * 缺省的白名单项跳过不报错。
 */
public final class ProcessEnv {

    private static final List<String> WINDOWS_KEYS = List.of(
            "PATH", "SystemRoot", "windir", "SystemDrive", "ComSpec", "PATHEXT",
            "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "APPDATA", "LOCALAPPDATA");

    private static final List<String> OTHER_KEYS = List.of(
            "PATH", "HOME", "USER", "LANG", "TMPDIR");

    private ProcessEnv() {
    }

    /** 当前平台的构建入口：只透传白名单项（父进程存在才放）加显式覆盖，API_KEY 类未声明变量被清。 */
    public static Map<String, String> build(Map<String, String> overrides) {
        return build(System.getProperty("os.name"), overrides);
    }

    /** 平台可注入版本，供纯单测覆盖 Windows 与非 Windows 两个分支 */
    static Map<String, String> build(String osName, Map<String, String> overrides) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : keysFor(osName)) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        if (overrides != null) {
            env.putAll(overrides);
        }
        return env;
    }

    static List<String> keysFor(String osName) {
        return isWindows(osName) ? WINDOWS_KEYS : OTHER_KEYS;
    }

    public static boolean isWindows() {
        return isWindows(System.getProperty("os.name"));
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase().contains("win");
    }
}
