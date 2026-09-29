package com.acode.team;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** A session-owned team snapshot; configPath is never taken from persisted JSON. */
public record Team(String name, String leadAgentID, String description,
                   List<TeammateInfo> members, Path configPath, long createdAt) {
    public Team {
        TeamManager.validateName(name);
        if (leadAgentID == null || leadAgentID.isBlank()) throw new IllegalArgumentException("Lead 标识不能为空");
        description = description == null ? "" : description;
        members = List.copyOf(members);
        configPath = Objects.requireNonNull(configPath).toAbsolutePath().normalize();
    }

    Team withMembers(List<TeammateInfo> roster) {
        return new Team(name, leadAgentID, description, roster, configPath, createdAt);
    }
}
