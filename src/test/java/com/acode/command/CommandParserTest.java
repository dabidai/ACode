package com.acode.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParserTest {

    @Test
    void parsesCommandNameAndRawArgument() {
        CommandParser.Parsed p = CommandParser.parse("/compact 保留 A B");
        assertTrue(p.command());
        assertFalse(p.slashOnly());
        assertEquals("compact", p.name());
        assertEquals("保留 A B", p.args());
    }

    @Test
    void lowercasesCommandName() {
        CommandParser.Parsed p = CommandParser.parse("/HELP");
        assertTrue(p.command());
        assertFalse(p.slashOnly());
        assertEquals("help", p.name());
        assertNull(p.args());
    }

    @Test
    void bareSlashIsSlashOnly() {
        CommandParser.Parsed p = CommandParser.parse("/");
        assertTrue(p.command());
        assertTrue(p.slashOnly());
        assertNull(p.name());
    }

    @Test
    void slashWithoutNameIsSlashOnly() {
        CommandParser.Parsed p = CommandParser.parse("/  ");
        assertTrue(p.command());
        assertTrue(p.slashOnly());
        assertNull(p.name());
    }

    @Test
    void commandNameFollowedByWhitespaceOnlyHasNoArgs() {
        CommandParser.Parsed p = CommandParser.parse("/memory   ");
        assertTrue(p.command());
        assertFalse(p.slashOnly());
        assertEquals("memory", p.name());
        assertNull(p.args());
    }

    @Test
    void plainTextIsNotACommand() {
        assertFalse(CommandParser.parse("abc").command());
    }

    @Test
    void nullInputIsNotACommand() {
        assertFalse(CommandParser.parse(null).command());
    }

    @Test
    void rawArgumentKeepsInternalWhitespaceAndNewlines() {
        CommandParser.Parsed p = CommandParser.parse("/compact 保留\nA   B");
        assertEquals("保留\nA   B", p.args());
    }

    @Test
    void pathLikeSlashInputIsParsedAsCommandAttempt() {
        CommandParser.Parsed p = CommandParser.parse("/src/main/java/App.java");
        assertTrue(p.command());
        assertEquals("src/main/java/app.java", p.name(), "命令名整体小写化");
        assertNull(p.args());
    }
}
