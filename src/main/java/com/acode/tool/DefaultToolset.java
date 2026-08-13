package com.acode.tool;

import com.acode.tool.impl.EditFileTool;
import com.acode.tool.impl.GlobTool;
import com.acode.tool.impl.GrepTool;
import com.acode.tool.impl.ReadFileTool;
import com.acode.tool.impl.WriteFileTool;

/**
 * 六个内置工具的组装入口。工具实现随 T4~T7 逐个就位后在此注册。
 */
public final class DefaultToolset {

    private DefaultToolset() {
    }

    /** 把全部内置工具注册进注册中心 */
    public static void registerAll(ToolRegistry registry) {
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        // T7 命令执行
    }
}
