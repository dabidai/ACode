package com.acode.tool;

import java.nio.file.Path;

/**
 * 工具执行上下文：提供相对路径的基准工作目录与 plan 模式标记，可按需扩展注入其他资源。
 */
public class ToolContext {

    private final Path workingDirectory;
    private final boolean planMode;

    public ToolContext(Path workingDirectory) {
        this(workingDirectory, false);
    }

    public ToolContext(Path workingDirectory, boolean planMode) {
        this.workingDirectory = workingDirectory;
        this.planMode = planMode;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /** plan 模式标记：true 表示 Agent 处于规划阶段（不执行写操作） */
    public boolean planMode() {
        return planMode;
    }

    /** 解析路径：绝对路径直接用，相对路径基于工作目录解析 */
    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : workingDirectory.resolve(p).normalize();
    }
}
