package com.acode.permission;

import com.acode.tool.Permission;

/**
 * 权限模式：四档整体信任策略，按工具分类（READ/WRITE/EXEC）查表决策。
 * PLAN 委托 DEFAULT，不单独维护矩阵。
 */
public enum PermissionMode {

    /** 日常开发：读不打断，写/命令需确认 */
    DEFAULT,

    /** 信任改代码：读/写放行，命令仍需确认 */
    ACCEPT_EDITS,

    /** 计划模式：只读放行，写/命令需确认（与 DEFAULT 矩阵一致） */
    PLAN,

    /** 全部放行，仅限完全受控环境；黑名单 + 沙箱仍生效 */
    BYPASS;

    /** 权限决策三态 */
    public enum Decision { ALLOW, DENY, ASK }

    public Decision decide(Permission category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, EXEC -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case EXEC -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    /** config 键 / /permission-mode 命令的合法值；null/非法返回 null。 */
    public static PermissionMode fromConfig(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "default" -> DEFAULT;
            case "acceptEdits" -> ACCEPT_EDITS;
            case "plan" -> PLAN;
            case "bypassPermissions" -> BYPASS;
            default -> null;
        };
    }

    /** 与 config 键一致的展示值 */
    public String configValue() {
        return switch (this) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case PLAN -> "plan";
            case BYPASS -> "bypassPermissions";
        };
    }
}
