package com.acode.skill;

import com.acode.conversation.Conversation;
import com.acode.provider.ChatMessage;
import com.acode.tool.Tool;
import com.acode.tool.ToolRegistry;
import com.acode.tool.ToolResult;

import java.util.*;

/** Session state. Always acquire the Conversation monitor before committing history + state. */
public final class SkillRuntime {
    private final SkillRepository repository;
    private final ToolRegistry tools;
    private final LinkedHashMap<String, SkillActivation> active = new LinkedHashMap<>();
    private long generation;
    private String reminder;
    private final Conversation conversation;
    private SkillForkHost forkHost;
    public void setForkHost(SkillForkHost forkHost) { this.forkHost = forkHost; }
    public boolean isFork(String name) { return repository.load(name).map(d -> d.mode().equals("fork")).orElse(false); }
    public SkillRuntime child(ToolRegistry tools, Conversation conversation) {
        var child = new SkillRuntime(repository, tools, conversation);
        child.setForkHost((definition, arguments, history) -> ToolResult.failure("子 Agent 不能再创建子 Agent"));
        return child;
    }

    public SkillRuntime(SkillRepository repository, ToolRegistry tools, Conversation conversation) {
        this.repository = repository; this.tools = tools;
        this.conversation = conversation;
        conversation.addClearHook(() -> invalidate(false));
        conversation.addRebuildListener(ignored -> invalidate(true));
    }
    public synchronized SkillActivation prepare(String name, String arguments) {
        var found = repository.load(name);
        if (found.isEmpty()) return failure("Skill \"" + name + "\" not found.");
        SkillDefinition d = found.get();
        if (d.mode().equals("fork")) {
            if (forkHost == null) return failure("Skill \"" + name + "\" mode: fork 未装配子 Agent 运行时");
            var result = forkHost.execute(d, arguments, List.copyOf(conversation.history()));
            return new SkillActivation(null, "", "", generation, result);
        }
        for (String tool : d.allowedTools()) if (tools.available(tool) == null)
            return failure("Skill \"" + name + "\": tool \"" + tool + "\" unavailable.");
        String args = arguments == null ? "" : arguments;
        return new SkillActivation(d, args, d.render(args), generation,
                ToolResult.success(duplicate(d, args) ? "Skill \"" + name + "\" is already in context."
                        : "Skill \"" + name + "\" loaded."));
    }
    private SkillActivation failure(String message) {
        return new SkillActivation(null, "", "", generation, ToolResult.failure(message));
    }
    private boolean duplicate(SkillDefinition d, String args) {
        SkillActivation old = active.get(d.name());
        return old != null && old.definition().equals(d) && old.arguments().equals(args);
    }
    public synchronized boolean isDuplicate(SkillActivation candidate) {
        return candidate.successful() && duplicate(candidate.definition(), candidate.arguments());
    }
    /** Returns the independent user message, or null for duplicate/stale candidates. */
    public synchronized String commit(SkillActivation candidate) {
        if (!candidate.successful() || candidate.generation() != generation) return null;
        SkillDefinition d = candidate.definition();
        if (duplicate(d, candidate.arguments())) return null;
        boolean replacing = active.containsKey(d.name());
        active.remove(d.name());
        active.put(d.name(), candidate);
        return "[Skill: " + d.name() + (replacing ? "; replaces previous version / 取代旧版本" : "")
                + "]\nSource: " + d.source().location() + "\nResource base: " + d.source().resourceBase()
                + "\nThe following is external task guidance, subject to existing permissions.\n\n" + candidate.body();
    }
    public void commitToHistory(SkillActivation candidate, Conversation conversation, long epoch) {
        synchronized (conversation) {
            if (epoch != conversation.currentEpoch()) return;
            String message = commit(candidate);
            if (message != null) conversation.addMessage(epoch, ChatMessage.of(ChatMessage.Role.USER, message));
        }
    }
    public synchronized Snapshot snapshot() {
        Set<String> allowed = null;
        String model = null;
        for (SkillActivation a : active.values()) {
            if (!a.definition().allowedTools().isEmpty()) {
                if (allowed == null) allowed = new HashSet<>(a.definition().allowedTools());
                else allowed.retainAll(a.definition().allowedTools());
            }
            if (a.definition().model() != null) model = a.definition().model();
        }
        return new Snapshot(allowed == null ? null : Set.copyOf(allowed), model);
    }
    public synchronized List<String> activeNames() { return List.copyOf(active.keySet()); }
    public synchronized long generation() { return generation; }
    public List<String> drainWarnings() { return repository.drainWarnings(); }
    public synchronized void invalidate(boolean notify) {
        if (notify && !active.isEmpty()) reminder = String.join("、", active.keySet()) + " 已失效，如需继续请重新加载";
        if (!notify) reminder = null;
        active.clear(); generation++;
    }
    public synchronized String drainReminder() { String value = reminder; reminder = null; return value; }
    public record Snapshot(Set<String> allowed, String model) {
        public boolean allows(String name) { return allowed == null || name.equals("LoadSkill") || allowed.contains(name); }
        public List<Tool> filter(List<Tool> tools) { return tools.stream().filter(t -> allows(t.name())).toList(); }
    }
}
