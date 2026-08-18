package com.acode.prompt;

import com.acode.prompt.EnvironmentDetector.EnvironmentSnapshot;
import com.acode.provider.ChatMessage;
import org.junit.jupiter.api.Test;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemReminderTest {

    @Test
    void wrapProducesXmlTaggedUserMessage() {
        ChatMessage m = SystemReminder.wrap("hello");
        assertEquals(USER, m.role());
        assertEquals("<system-reminder>\nhello\n</system-reminder>", m.content());
    }

    @Test
    void environmentWrapsRenderedSnapshot() {
        EnvironmentSnapshot env = new EnvironmentSnapshot("/work", "windows 11", "amd64",
                "bash", true, "main", "m", "2026-08-18");
        ChatMessage m = SystemReminder.environment(env);
        assertTrue(m.content().startsWith("<system-reminder>\n# Environment"));
        assertTrue(m.content().endsWith("</system-reminder>"));
    }

    @Test
    void isSystemReminderMatchesWrappedMessagesOnly() {
        assertTrue(SystemReminder.isSystemReminder(SystemReminder.wrap("x")));
        assertFalse(SystemReminder.isSystemReminder(ChatMessage.of(USER, "plain")));
    }
}
