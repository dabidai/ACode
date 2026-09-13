package com.acode.command;

import com.acode.context.ContextManager;
import com.acode.conversation.Conversation;
import com.acode.memory.MemoryManager;
import com.acode.memory.MemoryScope;
import com.acode.memory.MemoryStore;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.provider.FakeProvider;
import com.acode.session.SessionManager;
import com.acode.session.SessionStore;
import com.acode.tool.ToolRegistry;
import com.acode.ui.MenuEntry;
import com.acode.ui.UIController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandContextTest {

    @TempDir
    Path tempDir;

    /** 界面操作接口测试桩：全部方法空实现 */
    private static final class FakeUi implements UIController {
        @Override
        public void appendSystemMessage(String text) {
        }

        @Override
        public void submitUserInput(String text) {
        }

        @Override
        public void setPlanMode(boolean enabled) {
        }

        @Override
        public ContextUsage contextUsage() {
            return new ContextUsage(0, 0);
        }

        @Override
        public int selectMenu(List<MenuEntry> entries, String title) {
            return -1;
        }
    }

    private CommandContext build(String args) {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        FakeProvider provider = FakeProvider.streaming("hi");
        return new CommandContext(args, new FakeUi(),
                new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                        new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                                tempDir.resolve("permissions.project.yaml"),
                                tempDir.resolve("permissions.local.yaml"))),
                new ContextManager(tempDir, provider, conversation),
                new MemoryManager(
                        new MemoryStore(MemoryScope.project(() -> tempDir), MemoryScope.user(() -> tempDir)),
                        conversation, provider, false),
                new SessionManager(SessionStore.forProject(() -> tempDir), conversation),
                tempDir, new ToolRegistry(), "0.1.0");
    }

    @Test
    void accessorsReturnInjectedDependencies() {
        Conversation conversation = new Conversation("test-model", false, 8192, 200_000);
        FakeProvider provider = FakeProvider.streaming("hi");
        UIController ui = new FakeUi();
        PermissionChecker permissionChecker = new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                new RuleEngine(tempDir.resolve("permissions.user.yaml"),
                        tempDir.resolve("permissions.project.yaml"),
                        tempDir.resolve("permissions.local.yaml")));
        ContextManager contextManager = new ContextManager(tempDir, provider, conversation);
        MemoryManager memoryManager = new MemoryManager(
                new MemoryStore(MemoryScope.project(() -> tempDir), MemoryScope.user(() -> tempDir)),
                conversation, provider, false);
        SessionManager sessionManager = new SessionManager(SessionStore.forProject(() -> tempDir), conversation);
        ToolRegistry toolRegistry = new ToolRegistry();

        CommandContext ctx = new CommandContext("保留 A B", ui, permissionChecker, contextManager,
                memoryManager, sessionManager, tempDir, toolRegistry, "0.1.0");

        assertEquals("保留 A B", ctx.args(), "参数原文应原样可取");
        assertSame(ui, ctx.ui());
        assertSame(permissionChecker, ctx.permissionChecker());
        assertSame(contextManager, ctx.contextManager());
        assertSame(memoryManager, ctx.memoryManager());
        assertSame(sessionManager, ctx.sessionManager());
        assertSame(tempDir, ctx.workingDirectory());
        assertSame(toolRegistry, ctx.toolRegistry());
        assertEquals("0.1.0", ctx.version());
    }

    @Test
    void argsIsNullWhenCommandHasNoArguments() {
        assertNull(build(null).args());
    }

    @Test
    void contextExposesNoMutators() {
        assertFalse(Arrays.stream(CommandContext.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().startsWith("set")),
                "上下文不可变，不应暴露任何 setter");
    }
}
