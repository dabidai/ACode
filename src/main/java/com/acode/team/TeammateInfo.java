package com.acode.team;

import java.nio.file.Path;

/** Immutable roster entry. Termination removes the entry rather than adding a status. */
public record TeammateInfo(String name, String agentID, String agentType, String model,
                           Path worktreePath, BackendType backendType, Boolean isActive,
                           boolean planModeRequired) {
    public enum BackendType {
        IN_PROCESS;
        public String value() { return "in-process"; }
    }

    public TeammateInfo {
        TeamManager.validateName(name);
        if (agentID == null || agentID.isBlank()) throw new IllegalArgumentException("Agent 标识不能为空");
        if (backendType == null) backendType = BackendType.IN_PROCESS;
        if (worktreePath != null) worktreePath = worktreePath.toAbsolutePath().normalize();
    }

    public boolean active() { return !Boolean.FALSE.equals(isActive); }

    public TeammateInfo withActive(boolean active) {
        return new TeammateInfo(name, agentID, agentType, model, worktreePath, backendType,
                active, planModeRequired);
    }
}
