package com.acode.permission;

import com.acode.permission.DangerousCommandDetector.Detection;
import com.acode.permission.PermissionMode.Decision;
import com.acode.tool.Permission;
import com.acode.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限决策检查器：一次工具调用的八步决策链（① 内容提取 → ② 危险命令 → ③ 安全命令 →
 * ④ 路径沙箱 → ⑤ plan 例外 → ⑥ 规则 → ⑦ 会话级「始终允许」→ ⑧ 权限模式矩阵），
 * 串联五层防线：危险命令检测、路径沙箱、规则引擎、模式矩阵四层组件 + UI 层 HITL 确认。
 * 任一判定 ALLOW/DENY 即返回，只有 ASK 才打扰用户。
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

    /** 工具 → 内容字段兜底（工具未覆写 contentField() 时用；匿名测试工具零改动） */
    static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    private final Path projectRoot;
    private final PathSandbox sandbox;
    private final DangerousCommandDetector detector;
    private final RuleEngine ruleEngine;
    private final Set<String> allowAlwaysRules = ConcurrentHashMap.newKeySet();
    private volatile PermissionMode mode;
    private final List<Path> extraSandboxRoots;

    public PermissionChecker(PermissionMode mode, Path projectRoot, RuleEngine ruleEngine) {
        this(mode, projectRoot, ruleEngine, List.of());
    }

    /** 额外允许根（两级记忆根等）透传给路径沙箱；不传时行为与三参构造一致 */
    public PermissionChecker(PermissionMode mode, Path projectRoot, RuleEngine ruleEngine,
                             List<Path> extraSandboxRoots) {
        this.mode = mode;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.sandbox = new PathSandbox(projectRoot, extraSandboxRoots);
        this.detector = new DangerousCommandDetector();
        this.ruleEngine = ruleEngine;
        this.extraSandboxRoots = List.copyOf(extraSandboxRoots);
    }

    /** Same rules and sandbox, but no inherited session approvals; decisions are intersected. */
    public PermissionChecker child(PermissionMode requested) {
        return child(requested, projectRoot);
    }

    /** Host-allocated worktree replaces the child sandbox root; rule/mode ceilings remain. */
    public PermissionChecker child(PermissionMode requested, Path allocatedRoot) {
        PermissionMode ceiling = mode;
        var parentBoundary = new PermissionChecker(ceiling, allocatedRoot, ruleEngine, extraSandboxRoots);
        return new PermissionChecker(stricter(ceiling, requested), allocatedRoot, ruleEngine, extraSandboxRoots) {
            @Override public CheckResult check(Tool tool, JsonNode args) {
                return check(tool, args, null);
            }
            @Override public CheckResult check(Tool tool, JsonNode args, Path workingDirectory) {
                CheckResult parent = parentBoundary.check(tool, args, workingDirectory);
                CheckResult child = super.check(tool, args, workingDirectory);
                if (parent.decision() == Decision.DENY) return parent;
                if (child.decision() == Decision.DENY) return child;
                if (parent.decision() == Decision.ASK || child.decision() == Decision.ASK) return CheckResult.ask();
                return CheckResult.allow();
            }
        };
    }
    private static PermissionMode stricter(PermissionMode a, PermissionMode b) {
        if (a == PermissionMode.PLAN || b == PermissionMode.PLAN) return PermissionMode.PLAN;
        if (a == PermissionMode.DEFAULT || b == PermissionMode.DEFAULT) return PermissionMode.DEFAULT;
        if (a == PermissionMode.ACCEPT_EDITS || b == PermissionMode.ACCEPT_EDITS) return PermissionMode.ACCEPT_EDITS;
        return PermissionMode.BYPASS;
    }

    /** For approval-gated background hooks: no implicit write authorization via hooks. */
    public PermissionChecker readOnly() {
        PermissionChecker source = this;
        return new PermissionChecker(PermissionMode.PLAN, projectRoot, ruleEngine, extraSandboxRoots) {
            @Override public CheckResult check(Tool tool, JsonNode args) { return check(tool, args, null); }
            @Override public CheckResult check(Tool tool, JsonNode args, Path workingDirectory) {
                if (tool == null || tool.permission() != Permission.READ) return CheckResult.deny("审批队员的 Hook 仅允许只读操作");
                return source.check(tool, args, workingDirectory);
            }
        };
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionMode mode() {
        return mode;
    }

    public void addAllowAlwaysRule(String toolName, String content) {
        allowAlwaysRules.add(allowAlwaysKey(toolName, content));
    }

    /** 会话级「始终允许」的键：写入与两处读取必须共用，否则授权会静默失效 */
    private static String allowAlwaysKey(String toolName, String content) {
        return toolName + ":" + content;
    }

    /** 「始终允许」持久化到本地规则文件；写回失败返回 false（R9，不抛异常）。 */
    public boolean appendLocalRule(String toolName, String content) {
        return ruleEngine.appendLocalRule(toolName, content);
    }

    /** 规则清单查询委托：/permission 无参列举三层规则（文件路径 + 规则摘要） */
    public List<RuleEngine.LayerRules> ruleLayers() {
        return ruleEngine.layers();
    }

    /** 内容提取：无对应字段返回 null（未注册工具跳过内容层，R6） */
    public static String extractContent(Tool tool, JsonNode args) {
        String field = fieldOf(tool);
        if (field == null || args == null || !args.isObject()) {
            return null;
        }
        JsonNode value = args.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** 内容字段：工具声明的 contentField() 优先，CONTENT_FIELDS 兜底 */
    private static String fieldOf(Tool tool) {
        String declared = tool.contentField();
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        return CONTENT_FIELDS.get(tool.name());
    }

    public CheckResult check(Tool tool, JsonNode args) {
        return check(tool, args, null);
    }

    /** The sandbox root stays fixed; relative targets must match the actual tool working directory. */
    public CheckResult check(Tool tool, JsonNode args, Path workingDirectory) {
        if (tool == null) {
            return CheckResult.ask();
        }
        String toolName = tool.name();
        String content = extractContent(tool, args);

        // ② 危险命令（EXEC 类）：硬拦截
        if (tool.permission() == Permission.EXEC && content != null) {
            Detection d = detector.detect(content);
            if (d.dangerous()) {
                return CheckResult.deny("危险命令：" + d.reason());
            }
            // ③ 安全命令（EXEC 类）：不打扰
            if (detector.isSafeCommand(content)) {
                return CheckResult.allow();
            }
        }

        String targetPath = content;
        if ("file_path".equals(fieldOf(tool)) && content != null && workingDirectory != null) {
            try { targetPath = workingDirectory.resolve(content).normalize().toString(); }
            catch (java.nio.file.InvalidPathException e) { return CheckResult.deny("非法文件路径"); }
        }

        // ④ 路径沙箱（内容字段为 file_path 的工具）：硬边界
        if ("file_path".equals(fieldOf(tool)) && content != null && !sandbox.check(targetPath)) {
            return CheckResult.deny(sandbox.denyReason(targetPath));
        }

        // ⑤ plan 例外（仅 permission_mode=plan、写类工具）：canonical 判断在沙箱之后，防逃逸
        if (mode == PermissionMode.PLAN
                && tool.permission() == Permission.WRITE
                && content != null
                && inPlansDir(targetPath)) {
            return CheckResult.allow();
        }

        // ⑥ 权限规则：deny 就地拒绝、allow 就地放行；
        // ask 不就地返回——先落第 ⑦ 层「始终允许」给已明确授权留门，未命中才返回询问，且不再走第 ⑧ 层模式矩阵
        if (content != null) {
            PermissionRule.RuleEffect effect = ruleEngine.evaluate(toolName, content);
            if (effect == PermissionRule.RuleEffect.DENY) {
                return CheckResult.deny("规则拒绝");
            }
            if (effect == PermissionRule.RuleEffect.ALLOW) {
                return CheckResult.allow();
            }
            if (effect == PermissionRule.RuleEffect.ASK) {
                if (allowAlwaysRules.contains(allowAlwaysKey(toolName, content))) {
                    return CheckResult.allow();
                }
                return CheckResult.ask();
            }
        }

        // ⑦ 会话级「始终允许」
        if (content != null && allowAlwaysRules.contains(allowAlwaysKey(toolName, content))) {
            return CheckResult.allow();
        }

        // ⑧ 权限模式矩阵
        return switch (mode.decide(tool.permission())) {
            case ALLOW -> CheckResult.allow();
            case ASK -> CheckResult.ask();
            case DENY -> CheckResult.deny("权限模式拒绝");
        };
    }

    private boolean inPlansDir(String path) {
        Path canonicalTarget = sandbox.canonical(path);
        Path canonicalRoot = sandbox.canonical(projectRoot.resolve(".acode/plans").toString());
        return canonicalTarget != null && canonicalRoot != null && canonicalTarget.startsWith(canonicalRoot);
    }
}
