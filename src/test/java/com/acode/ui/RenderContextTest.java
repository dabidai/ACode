package com.acode.ui;

import com.acode.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
