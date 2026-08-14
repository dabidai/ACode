package com.acode.ui;

import org.junit.jupiter.api.Test;

import static com.acode.ui.CommandRouter.Action.CHAT;
import static com.acode.ui.CommandRouter.Action.CLEAR;
import static com.acode.ui.CommandRouter.Action.DO;
import static com.acode.ui.CommandRouter.Action.HELP;
import static com.acode.ui.CommandRouter.Action.PLAN;
import static com.acode.ui.CommandRouter.Action.QUIT;
import static com.acode.ui.CommandRouter.Action.RESUME;
import static com.acode.ui.CommandRouter.Action.SKIP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRouterTest {

    @Test
    void quitCommandRoutesToQuit() {
        assertEquals(QUIT, CommandRouter.route("/quit"));
    }

    @Test
    void clearCommandRoutesToClear() {
        assertEquals(CLEAR, CommandRouter.route("/clear"));
    }

    @Test
    void helpCommandRoutesToHelp() {
        assertEquals(HELP, CommandRouter.route("/help"));
    }

    @Test
    void resumeCommandRoutesToResume() {
        assertEquals(RESUME, CommandRouter.route("/resume"));
    }

    @Test
    void planCommandRoutesToPlan() {
        assertEquals(PLAN, CommandRouter.route("/plan"));
    }

    @Test
    void doCommandRoutesToDo() {
        assertEquals(DO, CommandRouter.route("/do"));
    }

    @Test
    void normalMessageRoutesToChat() {
        assertEquals(CHAT, CommandRouter.route("你好，帮我写一个排序算法"));
    }

    @Test
    void messageLookingLikePathRoutesToChat() {
        assertEquals(CHAT, CommandRouter.route("/src/main/java/App.java"));
    }

    @Test
    void leadingWhitespaceIsIgnoredForCommands() {
        assertEquals(QUIT, CommandRouter.route("  /quit"));
    }

    @Test
    void trailingWhitespaceIsIgnoredForCommands() {
        assertEquals(CLEAR, CommandRouter.route("/clear  "));
    }

    @Test
    void blankInputRoutesToSkip() {
        assertEquals(SKIP, CommandRouter.route(""));
        assertEquals(SKIP, CommandRouter.route("   "));
    }

    @Test
    void nullInputRoutesToQuit() {
        assertEquals(QUIT, CommandRouter.route(null));
    }

    @Test
    void commandsAreCaseSensitive() {
        assertEquals(CHAT, CommandRouter.route("/Quit"));
    }

    @Test
    void commandNeedsLeadingSlash() {
        assertEquals(CHAT, CommandRouter.route("quit"));
    }

    @Test
    void helpTextListsAllCommands() {
        assertTrue(CommandRouter.HELP_TEXT.contains("/quit"));
        assertTrue(CommandRouter.HELP_TEXT.contains("/clear"));
        assertTrue(CommandRouter.HELP_TEXT.contains("/resume"));
        assertTrue(CommandRouter.HELP_TEXT.contains("/plan"));
        assertTrue(CommandRouter.HELP_TEXT.contains("/do"));
        assertTrue(CommandRouter.HELP_TEXT.contains("/help"));
    }
}
