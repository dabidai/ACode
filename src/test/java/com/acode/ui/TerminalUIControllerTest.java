package com.acode.ui;

import com.acode.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TerminalUIControllerTest {

    private static RenderContext renderContext() {
        return new RenderContext(new AppConfig());
    }

    private static TerminalUIController controller(OutputPane output, RenderContext render,
                                                   Consumer<String> submit, Consumer<Boolean> plan,
                                                   Supplier<UIController.ContextUsage> usage,
                                                   BiFunction<List<MenuEntry>, String, Integer> menu) {
        return new TerminalUIController(output, render, submit, plan, usage, menu);
    }

    @Test
    void appendSystemMessageWritesToOutputPaneAndLiveRegion() {
        OutputPane output = new OutputPane();
        StringWriter sw = new StringWriter();
        RenderContext render = renderContext();
        render.setLive(new LiveRegionRenderer(80, 24));
        render.setScreenWriter(sw);
        TerminalUIController ui = controller(output, render, s -> { }, b -> { },
                () -> new UIController.ContextUsage(0, 0), (e, t) -> -1);

        ui.appendSystemMessage("（测试消息）");

        assertEquals(List.of("（测试消息）"), output.lines(), "输出区应收到该行");
        assertEquals("（测试消息）\r\n", sw.toString(), "活跃区 writer 应收到同一行");
    }

    @Test
    void submitUserInputDelegatesToInjectedEntryPoint() {
        List<String> received = new ArrayList<>();
        TerminalUIController ui = controller(new OutputPane(), renderContext(),
                received::add, b -> { }, () -> new UIController.ContextUsage(0, 0), (e, t) -> -1);

        ui.submitUserInput("设计一个登录页");

        assertEquals(List.of("设计一个登录页"), received, "文本应原样交给注入的对话入口");
    }

    @Test
    void setPlanModeDelegatesToInjectedSetter() {
        List<Boolean> received = new ArrayList<>();
        TerminalUIController ui = controller(new OutputPane(), renderContext(),
                s -> { }, received::add, () -> new UIController.ContextUsage(0, 0), (e, t) -> -1);

        ui.setPlanMode(true);
        ui.setPlanMode(false);

        assertEquals(List.of(true, false), received, "开关值应原样交给注入的规划模式 setter");
    }

    @Test
    void contextUsageReturnsInjectedSupplierValue() {
        UIController.ContextUsage usage = new UIController.ContextUsage(1234, 200_000);
        TerminalUIController ui = controller(new OutputPane(), renderContext(),
                s -> { }, b -> { }, () -> usage, (e, t) -> -1);

        assertSame(usage, ui.contextUsage(), "上下文占用应来自注入的估算入口");
    }

    @Test
    void selectMenuDelegatesEntriesAndTitleAndReturnsResult() {
        List<MenuEntry> entries = List.of(MenuEntry.item("A"), MenuEntry.separator(), MenuEntry.item("B"));
        AtomicReference<List<MenuEntry>> capturedEntries = new AtomicReference<>();
        AtomicReference<String> capturedTitle = new AtomicReference<>();
        TerminalUIController ui = controller(new OutputPane(), renderContext(),
                s -> { }, b -> { }, () -> new UIController.ContextUsage(0, 0),
                (e, t) -> {
                    capturedEntries.set(e);
                    capturedTitle.set(t);
                    return 2;
                });

        assertEquals(2, ui.selectMenu(entries, "（菜单标题）"));
        assertSame(entries, capturedEntries.get(), "条目应原样交给注入的菜单入口");
        assertEquals("（菜单标题）", capturedTitle.get(), "标题应原样交给注入的菜单入口");
    }

    @Test
    void selectMenuReturnsMinusOneOnCancel() {
        TerminalUIController ui = controller(new OutputPane(), renderContext(),
                s -> { }, b -> { }, () -> new UIController.ContextUsage(0, 0), (e, t) -> -1);

        assertEquals(-1, ui.selectMenu(List.of(MenuEntry.item("A")), null), "取消应返回 -1");
    }
}
