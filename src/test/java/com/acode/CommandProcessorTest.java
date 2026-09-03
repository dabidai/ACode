package com.acode;

import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.ui.OutputPane;
import com.acode.ui.RenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProcessorTest {

    @TempDir
    Path tempDir;

    private PermissionChecker checker() {
        return new PermissionChecker(PermissionMode.DEFAULT, tempDir,
                new RuleEngine(tempDir.resolve("user.yaml"),
                        tempDir.resolve("project.yaml"), tempDir.resolve("local.yaml")));
    }

    /** tui / sessionManager 传 null：handlePermissionMode 不触碰终端与会话。 */
    private CommandProcessor processor(OutputPane output, RenderContext rc, PermissionChecker checker) {
        return new CommandProcessor(null, output, rc,
                new Conversation("m", false, 4096, 2000), null,
                () -> checker, s -> { }, b -> { }, List::of, s -> { });
    }

    private static RenderContext renderContextWith(Writer writer) {
        RenderContext rc = new RenderContext(new AppConfig());
        rc.setScreenWriter(writer);
        return rc;
    }

    @Test
    void permissionModeWithNullArgPrintsCurrentMode() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        CommandProcessor processor = processor(output, rc, checker());

        processor.handlePermissionMode(null, rc.liveRenderer(), writer);

        assertTrue(output.lines().contains("当前权限模式：default"),
                "无参数时应输出当前权限模式行");
        assertTrue(writer.toString().contains("当前权限模式：default"),
                "当前模式行应同步渲染进活跃区输出目标");
    }

    @Test
    void permissionModeWithBlankArgPrintsCurrentMode() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        CommandProcessor processor = processor(output, rc, checker());

        processor.handlePermissionMode("   ", rc.liveRenderer(), writer);

        assertTrue(output.lines().contains("当前权限模式：default"),
                "空白参数应与无参数行为一致");
    }

    @Test
    void permissionModeInvalidValuePrintsErrorAndKeepsMode() {
        PermissionChecker checker = checker();
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        CommandProcessor processor = processor(output, rc, checker);

        processor.handlePermissionMode("bogus", rc.liveRenderer(), writer);

        assertTrue(output.lines().contains("（非法权限模式：bogus，可选：default/acceptEdits/plan/bypassPermissions）"),
                "非法值应输出完整错误提示");
        assertTrue(writer.toString().contains("非法权限模式：bogus"),
                "错误提示应同步渲染进活跃区输出目标");
        assertEquals(PermissionMode.DEFAULT, checker.mode(), "非法值不应改变当前权限模式");
    }

    @Test
    void permissionModeRejectsMultiWordArgument() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        CommandProcessor processor = processor(output, rc, checker());

        processor.handlePermissionMode("acceptEdits extra", rc.liveRenderer(), writer);

        assertTrue(output.lines().contains(
                        "（非法权限模式：acceptEdits extra，可选：default/acceptEdits/plan/bypassPermissions）"),
                "多余参数应视为非法值报错");
    }

    @Test
    void permissionModeValidValueSwitchesMode() {
        PermissionChecker checker = checker();
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        CommandProcessor processor = processor(output, rc, checker);

        processor.handlePermissionMode("acceptEdits", rc.liveRenderer(), writer);

        assertEquals(PermissionMode.ACCEPT_EDITS, checker.mode(), "合法值应切换权限模式");
        assertTrue(output.lines().contains("（已切换到权限模式：acceptEdits）"),
                "切档成功应输出确认行");
    }

    @Test
    void modelWithNoOptionsPrintsCurrentModel() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        Conversation conversation = new Conversation("agnes-2.0-flash", false, 4096, 2000);
        CommandProcessor processor = new CommandProcessor(null, output, rc,
                conversation, null,
                () -> checker(), s -> { }, b -> { }, List::of, s -> { });

        processor.handleModel("", rc.liveRenderer(), writer);

        assertTrue(output.lines().contains("当前模型：agnes-2.0-flash"),
                "无选项时应输出当前模型");
    }

    @Test
    void modelWithArgCallsModelSetter() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        Conversation conversation = new Conversation("old-model", false, 4096, 2000);
        String[] captured = {null};
        CommandProcessor processor = new CommandProcessor(null, output, rc,
                conversation, null,
                () -> checker(), s -> { }, b -> { }, List::of, s -> captured[0] = s);

        processor.handleModel("new-model", rc.liveRenderer(), writer);

        assertEquals("new-model", captured[0], "有参数时应调用 modelSetter");
        assertTrue(output.lines().contains("（已切换模型：new-model）"),
                "切档成功应输出确认行");
    }

    @Test
    void modelExtractsActualModelFromDisplayEntry() {
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        Conversation conversation = new Conversation("old", false, 4096, 2000);
        String[] captured = {null};
        CommandProcessor processor = new CommandProcessor(null, output, rc,
                conversation, null,
                () -> checker(), s -> { }, b -> { }, List::of, s -> captured[0] = s);

        processor.handleModel("opus  →  agnes-2.0-flash", rc.liveRenderer(), writer);

        assertEquals("opus  →  agnes-2.0-flash", captured[0],
                "直接参数应原样传递（不经过菜单解析）");
    }
}
