package com.acode.tool;

import java.nio.file.Path;

/**
 * 工具执行上下文：提供相对路径的基准工作目录，可按需扩展注入其他资源。
 */
public class ToolContext {

    private final Path workingDirectory;

    public ToolContext(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /** 解析路径：绝对路径直接用，相对路径基于工作目录解析 */
    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : workingDirectory.resolve(p).normalize();
    }
}
