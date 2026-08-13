package com.acode.tool;

/**
 * 工具权限级别元信息。参考 Claude Code 工具分级语义（只读 / 写 / 命令执行），
 * 本章仅标记、不拦截、不确认。
 */
public enum Permission {

    /** 只读：读文件、搜索代码 */
    READ,

    /** 写：创建、覆盖、修改文件 */
    WRITE,

    /** 命令执行 */
    EXEC
}
