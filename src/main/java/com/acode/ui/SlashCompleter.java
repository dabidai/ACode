package com.acode.ui;

import com.acode.command.Command;
import com.acode.command.CommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Locale;

/**
 * 斜杠命令 Tab 补全：候选来自注册中心可见清单（仅斜杠给全部、有前缀按前缀过滤、大小写不敏感），
 * 每条候选带命令名与描述；非斜杠开头或已进入参数区时不干预。
 */
public class SlashCompleter implements Completer {

    private final CommandRegistry registry;

    public SlashCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (!buffer.startsWith("/") || buffer.indexOf(' ') >= 0) {
            return;
        }
        String prefix = buffer.substring(1).toLowerCase(Locale.ROOT);
        for (Command command : registry.visible()) {
            if (command.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                String name = "/" + command.name();
                candidates.add(new Candidate(name, name, null, command.description(), null, null, true));
            }
        }
    }
}
