package com.acode.command;

/** 命令处理结果：本次执行后是否继续主循环。 */
public enum CommandResult {
    /** 继续主循环 */
    CONTINUE,
    /** 退出主循环 */
    EXIT
}
