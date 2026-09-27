package com.acode.subagent;

import com.acode.permission.PermissionMode;
import com.acode.provider.ChatMessage;
import com.acode.skill.*;
import com.acode.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public final class SkillForkAdapter implements SkillForkHost {
    private final Supplier<SubAgentRunner> runner;
    private final Supplier<Path> root;
    public SkillForkAdapter(Supplier<SubAgentRunner> runner, Supplier<Path> root) { this.runner = runner; this.root = root; }
    public ToolResult execute(SkillDefinition skill, String arguments, List<ChatMessage> history) {
        List<ChatMessage> selected = switch (skill.context()) {
            case "none" -> List.of();
            case "recent" -> ConversationHistory.recent(history);
            default -> List.copyOf(history);
        };
        var host = runner.get();
        var definition = new AgentDefinition("Skill:" + skill.name(), skill.description(), skill.allowedTools(), List.of(),
                skill.model() == null ? "inherit" : skill.model(), 20, PermissionMode.DEFAULT,
                host.parent().systemPrompt(), skill.source().location(), "skill");
        return host.run(definition, ForkBoilerplate.TEXT + "\n\n" + skill.render(arguments), skill.name(), skill.model(),
                root.get(), selected, call -> false);
    }
    private static final class ConversationHistory {
        static List<ChatMessage> recent(List<ChatMessage> history) {
            return com.acode.conversation.Conversation.sanitize(history.subList(Math.max(0, history.size() - 5), history.size()));
        }
    }
}
