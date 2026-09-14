package com.acode.ui;

import com.acode.config.AppConfig;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderContextTest {

    private static RenderContext renderContext() {
        return new RenderContext(new AppConfig());
    }

    @Test
    void liveRendererDefaultsTo80x24WithoutTui() {
        LiveRegionRenderer live = renderContext().liveRenderer();
        StringWriter out = new StringWriter();
        live.redraw(out, List.of("a".repeat(100)));
        assertTrue(out.toString().contains("a".repeat(80) + "\r\n" + "a".repeat(20)),
                "缺省渲染器应按 80 列折行（100 字符折为 80+20 两段）");
    }

    @Test
    void liveRendererNotNullWithoutInjection() {
        assertNotNull(renderContext().liveRenderer(), "无注入时也应返回可用的活跃区渲染器");
    }

    @Test
    void screenWriterDefaultsToStringWriterWithoutTui() {
        assertInstanceOf(StringWriter.class, renderContext().screenWriter(),
                "无终端且未注入时输出目标应为 StringWriter");
    }

    @Test
    void setLiveInjectionTakesPriority() {
        RenderContext rc = renderContext();
        LiveRegionRenderer injected = new LiveRegionRenderer(10, 24);
        rc.setLive(injected);
        assertSame(injected, rc.liveRenderer(), "注入的活跃区渲染器应优先返回同一实例");

        StringWriter out = new StringWriter();
        rc.liveRenderer().redraw(out, List.of("a".repeat(100)));
        assertTrue(out.toString().contains("a".repeat(10) + "\r\n"),
                "注入渲染器生效后应按其宽度（10 列）折行");
    }

    @Test
    void setScreenWriterInjectionTakesPriority() {
        RenderContext rc = renderContext();
        StringWriter injected = new StringWriter();
        rc.setScreenWriter(injected);
        assertSame(injected, rc.screenWriter(), "注入的输出目标应优先返回同一实例");
    }

    @Test
    void liveRendererIsReusedAcrossCallsWhenTuiAttached() throws Exception {
        RenderContext rc = renderContext();
        try (AcodeTerminal tui = virtualTerminal(80, 24)) {
            rc.attachTui(tui);
            assertSame(rc.liveRenderer(), rc.liveRenderer(),
                    "真实终端路径下必须复用同一渲染器，否则 rowsWritten 状态会分散到各实例、重绘错位");
        }
    }

    @Test
    void attachTuiInvalidatesCachedLiveRenderer() throws Exception {
        RenderContext rc = renderContext();
        try (AcodeTerminal first = virtualTerminal(80, 24);
             AcodeTerminal second = virtualTerminal(120, 40)) {
            rc.attachTui(first);
            LiveRegionRenderer before = rc.liveRenderer();
            rc.attachTui(second);
            assertNotSame(before, rc.liveRenderer(), "换终端后缓存必须失效，否则会按旧终端尺寸定位");
        }
    }

    /** 测试用虚拟终端：固定尺寸、无系统终端依赖 */
    private static AcodeTerminal virtualTerminal(int columns, int rows) throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .size(new Size(columns, rows))
                .build();
        return new AcodeTerminal(terminal);
    }
}
