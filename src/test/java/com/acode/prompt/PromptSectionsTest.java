package com.acode.prompt;

import com.acode.prompt.PromptBuilder.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSectionsTest {

    private static final List<Section> ALL = List.of(
            PromptSections.identitySection(),
            PromptSections.behaviorSection(),
            PromptSections.toolUsageSection(),
            PromptSections.codeQualitySection(),
            PromptSections.securitySection(),
            PromptSections.taskPatternSection(),
            PromptSections.outputStyleSection());

    @Test
    void allSectionsNonEmptyAndEnglish() {
        for (Section s : ALL) {
            assertFalse(s.content().isBlank(), s.name() + " content must be non-empty");
            assertFalse(s.content().matches("(?s).*[\\u4e00-\\u9fff].*"),
                    s.name() + " content must be English");
        }
    }

    @Test
    void namesMatchSevenModules() {
        List<String> names = ALL.stream().map(Section::name).sorted().toList();
        assertEquals(List.of("Behavior", "CodeQuality", "Identity", "OutputStyle",
                "Security", "TaskPattern", "ToolUsage"), names);
    }

    @Test
    void prioritiesAreUniqueAscending() {
        List<Integer> priorities = ALL.stream().map(Section::priority).sorted().toList();
        assertEquals(List.of(0, 10, 20, 30, 40, 50, 60), priorities);
        assertEquals(7, priorities.stream().distinct().count(), "priorities must be unique");
    }

    @Test
    void identityHasSecurityRedLines() {
        String c = PromptSections.identitySection().content();
        assertTrue(c.contains("security vulnerabilities"));
        assertTrue(c.contains("command injection"));
        assertTrue(c.contains("NEVER generate or guess URLs"));
    }

    @Test
    void behaviorExplainsSystemReminderAndInjection() {
        String c = PromptSections.behaviorSection().content();
        assertTrue(c.contains("<system-reminder>"));
        assertTrue(c.contains("denies a tool call"));
        assertTrue(c.contains("prompt injection"));
    }

    @Test
    void toolUsageHasSixToolMappings() {
        String c = PromptSections.toolUsageSection().content();
        assertTrue(c.contains("Use ReadFile instead of cat"));
        assertTrue(c.contains("Use EditFile instead of sed"));
        assertTrue(c.contains("Use WriteFile instead of echo"));
        assertTrue(c.contains("Use Glob instead of find"));
        assertTrue(c.contains("Use Grep instead of grep"));
        assertTrue(c.contains("Reserve Bash exclusively"));
    }

    @Test
    void codeQualityHasCommentAndScopeRules() {
        String c = PromptSections.codeQualitySection().content();
        assertTrue(c.contains("Default to writing no comments"));
        assertTrue(c.contains("WHY is non-obvious"));
        assertTrue(c.contains("Three similar lines is better than a premature abstraction"));
        assertTrue(c.contains("beyond what the task requires"));
    }

    @Test
    void securityHasDangerousCommandsAndHooks() {
        String c = PromptSections.securitySection().content();
        assertTrue(c.contains("rm -rf"));
        assertTrue(c.contains("force push"));
        assertTrue(c.contains("dropping database tables"));
        assertTrue(c.contains("--no-verify"));
    }

    @Test
    void taskPatternHasExploratoryAndReadFirstRules() {
        String c = PromptSections.taskPatternSection().content();
        assertTrue(c.contains("2-3 sentences"));
        assertTrue(c.contains("read-only exploration"));
        assertTrue(c.contains("read it first"));
        assertTrue(c.contains("verify it works"));
    }

    @Test
    void outputStyleHasReferenceFormat() {
        String c = PromptSections.outputStyleSection().content();
        assertTrue(c.contains("file_path:line_number"));
        assertTrue(c.contains("emojis"));
        assertTrue(c.contains("one or two sentences"));
    }
}
