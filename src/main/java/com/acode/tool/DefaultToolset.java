package com.acode.tool;

/**
 * 六个内置工具的组装入口。工具实现随 T4~T7 逐个就位后在此注册。
 */
public final class DefaultToolset {

    private DefaultToolset() {
    }

    /** 把全部内置工具注册进注册中心 */
    public static void registerAll(ToolRegistry registry) {
        // T4 读文件 / 写文件
        // T5 多段编辑
        // T6 搜索：glob 匹配路径 / grep 搜内容
        // T7 命令执行
    }
}
