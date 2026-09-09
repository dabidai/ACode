package com.acode;

import com.acode.config.AppConfig;
import com.acode.conversation.Conversation;
import com.acode.permission.PermissionChecker;
import com.acode.permission.PermissionMode;
import com.acode.permission.RuleEngine;
import com.acode.ui.InputPane;
import com.acode.ui.LiveRegionRenderer;
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

    /** 记录型 RowRewriter：捕获原地重写的行数与文本，替代真实的 JLine printAbove。 */
    private static final class RecordingRewriter implements InputPane.RowRewriter {
        private int calls = 0;
        private int rowsAbove = -1;
        private String text = "";

        @Override
        public void rewriteRowAboveInput(int rowsAbove, String text) {
            calls++;
            this.rowsAbove = rowsAbove;
            this.text = text;
        }
    }

    @Test
    void shiftTabRewritesModeLineThroughJLineWhenWaitingFrameIsFresh() {
        PermissionChecker checker = checker();
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        LiveRegionRenderer live = new LiveRegionRenderer(80, 24);
        live.renderWaitingFrame(writer, "MODE", "DIV", "FOOTDIV", "FOOTMODEL");
        rc.setLive(live);
        writer.getBuffer().setLength(0);
        CommandProcessor processor = processor(output, rc, checker);
        RecordingRewriter rewriter = new RecordingRewriter();

        processor.cyclePermissionMode(live, rewriter);

        assertEquals(PermissionMode.ACCEPT_EDITS, checker.mode(), "Shift+Tab 应切到下一档");
        assertEquals(1, rewriter.calls, "新鲜等待帧上应原地重写模式提示行");
        assertEquals(LiveRegionRenderer.WAITING_FRAME_ROWS, rewriter.rowsAbove,
                "模式提示行恒在输入区顶行上方 2 行（模式提示行 + 分隔线）");
        assertTrue(rewriter.text.contains("[acceptEdits]") && rewriter.text.contains("plan"),
                "应写入切换后的模式提示文本");
        assertEquals("", writer.toString(),
                "不得再直接往终端 writer 写光标序列：那会让 JLine 的 Display 缓存失同步、"
                        + "必须敲一次 Enter 才能继续输入");
        assertTrue(output.lines().isEmpty(),
                "原地更新不应向 OutputPane 追加已提交行（避免堆叠新行）");
    }

    @Test
    void shiftTabDefersWithoutRewritingWhenWaitingFrameIsStale() {
        PermissionChecker checker = checker();
        OutputPane output = new OutputPane();
        StringWriter writer = new StringWriter();
        RenderContext rc = renderContextWith(writer);
        LiveRegionRenderer live = new LiveRegionRenderer(80, 24);
        live.renderWaitingFrame(writer, "MODE", "DIV", "FOOTDIV", "FOOTMODEL");
        live.appendCommitted(writer, "命令输出"); // 帧不再新鲜：模式提示行已被推远
        rc.setLive(live);
        writer.getBuffer().setLength(0);
        CommandProcessor processor = processor(output, rc, checker);
        RecordingRewriter rewriter = new RecordingRewriter();

        processor.cyclePermissionMode(live, rewriter);

        assertEquals(PermissionMode.ACCEPT_EDITS, checker.mode(), "模式仍应切换");
        assertEquals(0, rewriter.calls, "帧已陈旧时不得原地重写（模式行已不在固定偏移处）");
        assertEquals("", writer.toString(), "改走延迟消息，不写任何光标序列");
        assertTrue(output.lines().isEmpty(), "延迟消息要等下一次命令输出时才冲刷");
    }
}
