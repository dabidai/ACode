package com.acode.prompt;

import com.acode.provider.ChatMessage;

/**
 * Wraps content into a role=USER message tagged with {@code <system-reminder>}.
 * The tag is a transport convention, not a higher-priority instruction channel:
 * on the wire it is still a user message.
 */
public final class SystemReminder {

    public static final String OPEN = "<system-reminder>";
    public static final String CLOSE = "</system-reminder>";

    private SystemReminder() {}

    public static ChatMessage wrap(String content) {
        return ChatMessage.of(ChatMessage.Role.USER, OPEN + "\n" + content + "\n" + CLOSE);
    }

    public static ChatMessage environment(EnvironmentDetector.EnvironmentSnapshot snapshot) {
        return wrap(EnvironmentDetector.render(snapshot));
    }

    /** Test/debug helper: true when the message text starts with the opening tag. */
    public static boolean isSystemReminder(ChatMessage message) {
        return message.content().startsWith(OPEN);
    }
}
