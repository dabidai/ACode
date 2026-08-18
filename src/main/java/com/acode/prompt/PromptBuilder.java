package com.acode.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles a system prompt from prioritized sections.
 * Sections are sorted by ascending priority; blank content is filtered;
 * sections are joined with two newlines.
 */
public class PromptBuilder {

    public record Section(String name, int priority, String content) {}

    private final List<Section> sections = new ArrayList<>();

    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));
        var parts = new ArrayList<String>();
        for (Section s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    /** Fixed assembly of the seven modules (identity first, output style last). */
    public static String buildSystemPrompt() {
        return new PromptBuilder()
                .add(PromptSections.identitySection())
                .add(PromptSections.behaviorSection())
                .add(PromptSections.toolUsageSection())
                .add(PromptSections.codeQualitySection())
                .add(PromptSections.securitySection())
                .add(PromptSections.taskPatternSection())
                .add(PromptSections.outputStyleSection())
                .build();
    }
}
