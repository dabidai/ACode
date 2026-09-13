package com.acode.command;

import java.util.List;

/**
 * 命令定义：名称、别名列表、描述、用法示例、类型、参数用法说明、是否隐藏、处理函数。
 * 别名与正式名完全等价；查找时名称统一小写化，参数不受影响。
 * 不设"参数是否必需"——无参数的命令一律有确定含义（查看）。
 */
public record Command(String name, List<String> aliases, String description, String usage,
                      CommandType type, String argumentHint, boolean hidden, Handler handler) {

    /** 处理函数：入参为参数原文（无参数时为 null），返回命令执行结果。 */
    @FunctionalInterface
    public interface Handler {
        CommandResult execute(String args);
    }

    public Command {
        aliases = List.copyOf(aliases);
    }
}
