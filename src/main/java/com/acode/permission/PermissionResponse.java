package com.acode.permission;

/**
 * HITL 三选一答复：放行 / 始终允许 / 拒绝。
 * 「始终允许」由执行器转为会话级记忆 + 本地规则文件持久化。
 */
public enum PermissionResponse {
    ALLOW,
    ALLOW_ALWAYS,
    DENY
}
