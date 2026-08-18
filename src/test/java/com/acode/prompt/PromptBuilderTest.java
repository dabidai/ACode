package com.acode.prompt;

import com.acode.prompt.PromptBuilder.Section;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @Test
    void sortsSectionsByPriorityAscending() {
        String result = new PromptBuilder()
                .add(new Section("OutputStyle", 60, "style"))
                .add(new Section("Identity", 0, "identity"))
                .add(new Section("ToolUsage", 20, "tools"))
                .build();
        assertEquals("identity\n\ntools\n\nstyle", result);
    }

    @Test
    void filtersOutBlankSections() {
        String result = new PromptBuilder()
                .add(new Section("A", 0, "a"))
                .add(new Section("B", 10, "   "))
                .add(new Section("C", 20, null))
                .build();
        assertEquals("a", result);
    }

    @Test
    void joinsSectionsWithTwoNewlines() {
        String result = new PromptBuilder()
                .add(new Section("A", 0, "first"))
                .add(new Section("B", 10, "second"))
                .build();
        assertEquals("first\n\nsecond", result);
    }

    @Test
    void buildSystemPromptAssemblesSevenModulesInOrder() {
        String result = PromptBuilder.buildSystemPrompt();
        int identity = result.indexOf("You are ACode");
        int behavior = result.indexOf("# Behavior");
        int tools = result.indexOf("# Using your tools");
        int quality = result.indexOf("# Code quality");
        int security = result.indexOf("# Executing actions with care");
        int pattern = result.indexOf("# Doing tasks");
        int style = result.indexOf("# Tone and style");
        assertTrue(identity >= 0, "identity module present");
        assertTrue(style >= 0, "output style module present");
        assertTrue(identity < behavior && behavior < tools && tools < quality
                        && quality < security && security < pattern && pattern < style,
                "modules assembled in priority order");
    }
}
