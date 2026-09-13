package com.acode.command;

/** 命令类型：仅作元数据（帮助分组与说明用），框架不对它做执行派发。 */
public enum CommandType {
    /** 本地命令：纯本地执行，结果以系统消息形式显示 */
    LOCAL,
    /** 本地界面命令：本地执行，并改变界面状态或交互模式 */
    LOCAL_UI,
    /** 提示词命令：构造预设提示词交给 Agent 执行 */
    PROMPT
}
