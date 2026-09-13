package com.acode.command;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 命令调度器：一次输入的执行链 —— 解析 → 裸斜杠走帮助 → 查找 → 未命中给"错误 + 指向帮助" →
 * 命中则建上下文并执行 → 执行异常兜底为一行错误。
 * 参数判定（保留词 / 合法取值）与参数不合法时的用法提示由各命令自己负责，调度器只管解析、查找与
 * 错误兜底；参数原文原样交给处理函数，不做语义判断。
 */
public final class CommandDispatcher {

    private final CommandRegistry registry;
    private final Function<String, CommandContext> contextFactory;
    private final Consumer<String> chatHandler;

    /**
     * @param contextFactory 以参数原文构造执行上下文：每次执行只有 args 不同，
     *                       其余依赖由装配方打包进工厂
     * @param chatHandler    非命令输入的对话通道
     */
    public CommandDispatcher(CommandRegistry registry, Function<String, CommandContext> contextFactory,
                             Consumer<String> chatHandler) {
        this.registry = registry;
        this.contextFactory = contextFactory;
        this.chatHandler = chatHandler;
    }

    /** 处理一次输入，返回主循环继续（CONTINUE）或退出（EXIT）。空输入忽略；非命令走对话通道。 */
    public CommandResult dispatch(String input) {
        if (input == null || input.isBlank()) {
            return CommandResult.CONTINUE;
        }
        CommandParser.Parsed parsed = CommandParser.parse(input);
        if (!parsed.command()) {
            chatHandler.accept(input);
            return CommandResult.CONTINUE;
        }
        if (parsed.slashOnly()) {
            Command help = registry.find("help");
            return help != null ? execute(help, null) : CommandResult.CONTINUE;
        }
        Command command = registry.find(parsed.name());
        if (command == null) {
            contextFactory.apply(parsed.args()).ui().appendSystemMessage(
                    "未知命令：/" + rawName(input) + "（输入 /help 查看可用命令）");
            return CommandResult.CONTINUE;
        }
        return execute(command, parsed.args());
    }

    /** 执行命令：处理函数抛异常时兜底为一行错误，主循环继续 */
    private CommandResult execute(Command command, String args) {
        CommandContext ctx = contextFactory.apply(args);
        try {
            return command.handler().execute(ctx);
        } catch (RuntimeException e) {
            ctx.ui().appendSystemMessage("命令执行失败：" + e.getMessage());
            return CommandResult.CONTINUE;
        }
    }

    /** 用户输入的命令名原文（回显用，保持原大小写；首个空白处截断） */
    private static String rawName(String input) {
        int end = 1;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
            end++;
        }
        return input.substring(1, end);
    }
}
