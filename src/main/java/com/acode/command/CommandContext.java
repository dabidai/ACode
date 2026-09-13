package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.memory.MemoryManager;
import com.acode.permission.PermissionChecker;
import com.acode.session.SessionManager;
import com.acode.tool.ToolRegistry;
import com.acode.ui.UIController;

import java.nio.file.Path;

/**
 * 命令执行上下文：把命令实际需要的依赖一次性打包，处理函数经它取资源，构造后不可变。
 * 只收有人用的依赖——不收 Agent 与裸会话对象（提示词命令经界面操作接口提交、
 * 压缩与状态经各自门面），避免空壳字段。
 */
public record CommandContext(String args, UIController ui, PermissionChecker permissionChecker,
                             ContextManager contextManager, MemoryManager memoryManager,
                             SessionManager sessionManager, Path workingDirectory,
                             ToolRegistry toolRegistry, String version) {
}
