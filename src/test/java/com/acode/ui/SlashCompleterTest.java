package com.acode.ui;

import com.acode.command.Command;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.command.CommandType;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCompleterTest {

    private static Command command(String name) {
        return new Command(name, List.of(), "desc of " + name, "/" + name,
                CommandType.LOCAL, null, false, ctx -> CommandResult.CONTINUE);
    }

    private static Command hidden(String name) {
        return new Command(name, List.of(), "hidden " + name, "/" + name,
                CommandType.LOCAL, null, true, ctx -> CommandResult.CONTINUE);
    }

    private static ParsedLine parsed(String text) {
        return new ParsedLine() {
            @Override
            public String word() {
                return text;
            }

            @Override
            public int wordCursor() {
                return text.length();
            }

            @Override
            public int wordIndex() {
                return 0;
            }

            @Override
            public List<String> words() {
                return List.of(text);
            }

            @Override
            public String line() {
                return text;
            }

            @Override
            public int cursor() {
                return text.length();
            }
        };
    }

    private static List<Candidate> complete(CommandRegistry registry, String buffer) {
        List<Candidate> candidates = new ArrayList<>();
        new SlashCompleter(registry).complete(null, parsed(buffer), candidates);
        return candidates;
    }

    private static List<String> expectedValues(CommandRegistry registry, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return registry.visible().stream()
                .map(Command::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .map(name -> "/" + name)
                .toList();
    }

    @Test
    void slashAloneOffersAllVisibleCommandsIncludingQuit() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(command("compact"));
        registry.register(command("resume"));
        registry.register(command("quit"));

        List<String> values = complete(registry, "/").stream().map(Candidate::value).toList();

        assertEquals(expectedValues(registry, ""), values);
        assertTrue(values.contains("/quit"));
    }

    @Test
    void prefixNarrowsToCommandsStartingWithIt() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("compact"));
        registry.register(command("comment"));
        registry.register(command("help"));
        registry.register(command("quit"));

        List<String> values = complete(registry, "/com").stream().map(Candidate::value).toList();

        assertEquals(expectedValues(registry, "com"), values);
        assertEquals(List.of("/compact", "/comment"), values);
    }

    @Test
    void hiddenCommandsNeverAppearAsCandidates() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(hidden("internal"));
        registry.register(command("quit"));

        List<String> values = complete(registry, "/").stream().map(Candidate::value).toList();

        assertEquals(expectedValues(registry, ""), values);
        assertFalse(values.contains("/internal"));
        assertEquals(registry.visible().size(), values.size());
    }

    @Test
    void prefixMatchingIsCaseInsensitive() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(command("compact"));

        assertEquals(List.of("/help"), complete(registry, "/HELP").stream().map(Candidate::value).toList());
        assertEquals(expectedValues(registry, "COM"), complete(registry, "/CoM").stream().map(Candidate::value).toList());
    }

    @Test
    void noMatchOffersNoCandidates() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(command("quit"));

        assertTrue(complete(registry, "/xyz").isEmpty());
    }

    @Test
    void eachCandidateCarriesCommandDescription() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(command("compact"));
        registry.register(command("quit"));

        List<Candidate> candidates = complete(registry, "/");
        assertEquals(registry.visible().stream().map(Command::description).toList(),
                candidates.stream().map(Candidate::descr).toList());
    }

    @Test
    void nonSlashInputIsLeftAlone() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));

        assertTrue(complete(registry, "hello").isEmpty());
    }

    @Test
    void inputAfterArgumentSpaceIsLeftAlone() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help"));
        registry.register(command("compact"));

        assertTrue(complete(registry, "/help more").isEmpty());
    }
}
