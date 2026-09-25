package com.acode.permission;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 危险命令检测（黑名单，启发式最佳努力）+ 安全命令白名单。
 * 黑名单拦截已知高风险模式（递归删根、格式化磁盘、写裸设备、fork bomb、管道执行远程脚本等），
 * 不承诺穷举混淆/参数变体（如 rm -rf --no-preserve-root /、base64 解码后执行）。
 */
public class DangerousCommandDetector {

    /** detect 返回：命中与否 + 中文原因 */
    public record Detection(boolean dangerous, String reason) {
        public static Detection safe() {
            return new Detection(false, "");
        }
    }

    /** 安全命令白名单：纯只读命令 + git 只读子命令 + 版本查询形式（数量不作契约） */
    static final Set<String> SAFE_COMMANDS = Set.of(
            // 纯只读命令
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "which", "whereis", "whoami", "hostname", "uname",
            "cal", "uptime", "df", "du", "free", "printenv",
            "file", "stat", "readlink", "realpath", "basename", "dirname",
            "uniq", "tr", "cut", "grep", "egrep", "fgrep",
            "diff", "comm", "true", "false", "test",
            // git 只读子命令（改状态/联网的 branch/tag/remote 已裁剪）
            "git status",
            "git rev-parse", "git ls-files", "git blame", "git stash list",
            // 解释器/构建工具的版本查询形式（非裸命令名）
            "go version",
            "node -v", "npm -v",
            "python --version", "pip list",
            "cargo --version", "rustc --version",
            "java -version", "java --version"
    );

    private final List<Pattern> dangerousPatterns;

    public DangerousCommandDetector() {
        this.dangerousPatterns = List.of(
                Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
                Pattern.compile("mkfs\\."),
                Pattern.compile("dd\\s+if=.*of=/dev/"),
                Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
                Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
                Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
                Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
                Pattern.compile(">\\s*/dev/sd")
        );
    }

    private static final String[] REASONS = {
            "递归强制删除根目录",
            "格式化磁盘",
            "直接写磁盘设备",
            "递归修改根目录权限",
            "fork bomb 进程炸弹",
            "管道执行远程脚本",
            "管道执行远程脚本",
            "覆盖磁盘设备"
    };

    /** 命中任一黑名单模式返回 (true, 中文原因)，全部不命中返回 (false, "") */
    public Detection detect(String command) {
        if (command == null) {
            return Detection.safe();
        }
        for (int i = 0; i < dangerousPatterns.size(); i++) {
            Matcher m = dangerousPatterns.get(i).matcher(command);
            if (m.find()) {
                return new Detection(true, REASONS[i]);
            }
        }
        return Detection.safe();
    }

    /**
     * 只读安全命令放行：先排除控制字符和各 shell 的命令组合、重定向、展开语法，
     * 再对白名单做「完全相等或 safe + " " 前缀」匹配（带子命令条目按完整子命令匹配）。
     */
    public boolean isSafeCommand(String command) {
        if (command == null) {
            return false;
        }
        // 检查原文，避免 trim 抹掉末尾换行或 NUL 后误判为单条命令。
        if (containsShellMetachar(command)) {
            return false;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (SAFE_COMMANDS.contains(trimmed)) {
            return true;
        }
        for (String safe : SAFE_COMMANDS) {
            if (trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsShellMetachar(String command) {
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (Character.isISOControl(c) || "&|;><$`%^!(){}".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }
}
