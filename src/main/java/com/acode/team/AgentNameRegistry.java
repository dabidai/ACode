package com.acode.team;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Name and ID share one address space, so lookup is never ambiguous. */
public final class AgentNameRegistry {
    private final Map<String, String> names = new LinkedHashMap<>();

    public synchronized void register(String name, String agentID) {
        TeamManager.validateName(name);
        if (agentID == null || agentID.isBlank() || agentID.equals("*"))
            throw new IllegalArgumentException("Agent 标识无效");
        if (resolve(name).isPresent() || resolve(agentID).isPresent())
            throw new IllegalArgumentException("队员名称或标识已存在：" + name);
        names.put(name, agentID);
    }

    public synchronized Optional<String> resolve(String address) {
        if (names.containsKey(address)) return Optional.of(names.get(address));
        return names.values().stream().filter(id -> id.equals(address)).findFirst();
    }

    public synchronized void unregister(String nameOrID) {
        resolve(nameOrID).ifPresent(id -> names.values().removeIf(id::equals));
    }

    public synchronized Map<String, String> snapshot() { return Map.copyOf(names); }
}
