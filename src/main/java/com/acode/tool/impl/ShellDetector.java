package com.acode.tool.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 运行时检测 shell：优先 Git Bash（环境变量 GIT_BASH → 常见安装路径），
 * 找不到则回退系统默认 shell（Windows 下为 cmd）。检测结果为可执行前缀。
 */
public class ShellDetector {

    private static final List<String> DEFAULT_GIT_BASH_PATHS = List.of(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
            "C:\\Git\\bin\\bash.exe",
            "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe");

    private final List<String> commandPrefix;
    private final String shellName;

    /** 使用默认 Git Bash 候选路径检测 */
    public ShellDetector() {
        this(DEFAULT_GIT_BASH_PATHS);
    }

    /**
     * 用给定候选路径检测 Git Bash（便于测试注入），找不到回退系统默认。
     *
     * @param candidateGitBashPaths 按优先级排列的 bash.exe 候选路径
     */
    public ShellDetector(List<String> candidateGitBashPaths) {
        String bash = findBash(candidateGitBashPaths);
        if (bash != null) {
            this.commandPrefix = List.of(bash, "-lc");
            this.shellName = "git-bash";
        } else {
            this.commandPrefix = List.of("cmd", "/c");
            this.shellName = "cmd";
        }
    }

    /** shell 调用前缀，如 [".../bash.exe","-lc"] 或 ["cmd","/c"] */
    public List<String> commandPrefix() {
        return commandPrefix;
    }

    /** 命中的 shell 名：git-bash 或 cmd */
    public String shellName() {
        return shellName;
    }

    private static String findBash(List<String> candidates) {
        String envBash = System.getenv("GIT_BASH");
        if (envBash != null && !envBash.isBlank() && Files.isRegularFile(Path.of(envBash))) {
            return envBash;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()
                    && Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
