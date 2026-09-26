package com.acode.skill;

import com.acode.command.*;
import java.util.*;
import java.util.function.Consumer;

public final class SkillManager {
    private final SkillRepository repository;
    private final SkillExecutor executor;
    private final CommandRegistry commands;
    private final Runnable refreshPrompt;
    private final Consumer<String> warning;
    private List<Command> owned = List.of();

    public SkillManager(SkillRepository repository, SkillRuntime runtime, CommandRegistry commands,
                        Runnable refreshPrompt, Consumer<String> warning) {
        this.repository = repository; this.executor = new SkillExecutor(runtime); this.commands = commands;
        this.refreshPrompt = refreshPrompt; this.warning = warning;
        commands.register(new Command("skill", List.of(), "查看或重载 Skill", "/skill [list|info <name>|reload]",
                CommandType.LOCAL, "list|info <name>|reload", false, this::manage));
    }
    public synchronized void reload() {
        synchronized (repository) {
            repository.reload();
            List<Command> next = repository.list().stream().map(d -> new Command(d.name(), List.of(),
                    SkillRepository.singleLine(d.description()) + " [skill]", "/" + d.name(), CommandType.PROMPT,
                    "<arguments>", false, ctx -> {
                        executor.execute(d.name(), ctx.args(), ctx.ui());
                        return CommandResult.CONTINUE;
                    })).toList();
            commands.replaceOwned(owned, next, refreshPrompt, warning);
            owned = next;
            repository.drainWarnings().forEach(warning);
        }
    }
    private CommandResult manage(CommandContext ctx) {
        String args = ctx.args() == null ? "list" : ctx.args().strip();
        if (args.isEmpty() || args.equals("list")) {
            for (SkillDefinition d : repository.list()) ctx.ui().appendSystemMessage(d.name() + ": "
                    + SkillRepository.singleLine(d.description()) + " [" + d.source().layer() + "] " + d.source().location());
        } else if (args.equals("reload")) {
            reload(); ctx.ui().appendSystemMessage("Skills reloaded.");
        } else if (args.startsWith("info ")) {
            String name = args.substring(5).strip();
            repository.load(name).ifPresentOrElse(d -> ctx.ui().appendSystemMessage(
                    "name: " + d.name() + "\ndescription: " + d.description() + "\nallowedTools: " + d.allowedTools()
                    + "\nmodel: " + d.model() + "\nmode: " + d.mode() + "\ncontext: " + d.context()
                    + "\nsource: " + d.source().layer() + " " + d.source().location() + "\n\n" + d.body()),
                    () -> ctx.ui().appendSystemMessage("Skill \"" + name + "\" not found."));
        } else ctx.ui().appendSystemMessage("用法：/skill [list|info <name>|reload]");
        repository.drainWarnings().forEach(ctx.ui()::appendSystemMessage);
        return CommandResult.CONTINUE;
    }
}
