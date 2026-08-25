package com.acode.permission;

import com.acode.permission.DangerousCommandDetector.Detection;
import com.acode.permission.PermissionMode.Decision;
import com.acode.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限决策检查器：一次工具调用的五层防线决策链。
 * ① 内容提取 → ② 危险命令 → ③ 安全命令 → ④ 路径沙箱 →
 * ⑤ plan 例外（canonical 判断、在沙箱之后）→ ⑥ 规则 →
 * ⑦ 会话级「始终允许」→ ⑧ 权限模式矩阵。任一判定 ALLOW/DENY 即返回，只有 ASK 才打扰用户。
 */
public class PermissionChecker {

    /** 决策结果：三态 + 纯原因（由 Agent 层统一加「权限拒绝：」前缀） */
    public record CheckResult(Decision decision, String reason) {
        public static CheckResult allow() {
            return new CheckResult(Decision.ALLOW, "");
        }

        public static CheckResult deny(String reason) {
            return new CheckResult(Decision.DENY, reason);
        }

        public static CheckResult ask() {
            return new CheckResult(Decision.ASK, "");
        }
    }

    /** 工具 → 内容字段（与六个工具的 ParamSpec 名一致） */
    static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    private static final Set<String> PATH_TOOLS = Set.of("ReadFile", "WriteFile", "EditFile");

    private final Path projectRoot;
    private final PathSandbox sandbox;
    private final DangerousCommandDetector detector;
    private final RuleEngine ruleEngine;
    private final Set<String> allowAlwaysRules = ConcurrentHashMap.newKeySet();
    private volatile PermissionMode mode;

    public PermissionChecker(PermissionMode mode, Path projectRoot, RuleEngine ruleEngine) {
        this.mode = mode;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.sandbox = new PathSandbox(projectRoot);
        this.detector = new DangerousCommandDetector();
        this.ruleEngine = ruleEngine;
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionMode mode() {
        return mode;
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        allowAlwaysRules.add(toolName + ":" + content);
    }

    /** 「始终允许」持久化到本地规则文件；写回失败返回 false（R9，不抛异常）。 */
    public boolean appendLocalRule(String toolName, String content) {
        return ruleEngine.appendLocalRule(toolName, content);
    }

    /** 内容提取：无对应字段返回 null（未注册工具跳过内容层，R6） */
    public static String extractContent(Tool tool, JsonNode args) {
        String field = CONTENT_FIELDS.get(tool.name());
        if (field == null || args == null || !args.isObject()) {
            return null;
        }
        JsonNode value = args.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    public CheckResult check(Tool tool, JsonNode args) {
        if (tool == null) {
            return CheckResult.ask();
        }
        String toolName = tool.name();
        String content = extractContent(tool, args);

        // ② 危险命令（仅 Bash）：硬拦截
        if ("Bash".equals(toolName) && content != null) {
            Detection d = detector.detect(content);
            if (d.dangerous()) {
                return CheckResult.deny("危险命令：" + d.reason());
            }
            // ③ 安全命令（仅 Bash）：不打扰
            if (detector.isSafeCommand(content)) {
                return CheckResult.allow();
            }
        }

        // ④ 路径沙箱（仅文件工具）：硬边界
        if (isPathTool(toolName) && content != null && !sandbox.check(content)) {
            return CheckResult.deny(sandbox.denyReason(content));
        }

        // ⑤ plan 例外（仅 permission_mode=plan、写工具）：canonical 判断在沙箱之后，防逃逸
        if (mode == PermissionMode.PLAN
                && (toolName.equals("WriteFile") || toolName.equals("EditFile"))
                && content != null
                && inPlansDir(content)) {
            return CheckResult.allow();
        }

        // ⑥ 权限规则：allow/deny 直接返回
        if (content != null) {
            PermissionRule.RuleEffect effect = ruleEngine.evaluate(toolName, content);
            if (effect != null) {
                return effect == PermissionRule.RuleEffect.ALLOW
                        ? CheckResult.allow()
                        : CheckResult.deny("规则拒绝");
            }
        }

        // ⑦ 会话级「始终允许」
        if (content != null && allowAlwaysRules.contains(toolName + ":" + content)) {
            return CheckResult.allow();
        }

        // ⑧ 权限模式矩阵
        return switch (mode.decide(tool.permission())) {
            case ALLOW -> CheckResult.allow();
            case ASK -> CheckResult.ask();
            case DENY -> CheckResult.deny("权限模式拒绝");
        };
    }

    private static boolean isPathTool(String toolName) {
        return PATH_TOOLS.contains(toolName);
    }

    private boolean inPlansDir(String path) {
        Path canonicalTarget = sandbox.canonical(path);
        Path canonicalRoot = sandbox.canonical(projectRoot.resolve(".acode/plans").toString());
        return canonicalTarget != null && canonicalRoot != null && canonicalTarget.startsWith(canonicalRoot);
    }
}
