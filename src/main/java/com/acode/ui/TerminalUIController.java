package com.acode.ui;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 界面操作接口的真实实现：不直接持有主循环宿主类，全部委托构造注入的协作者——
 * 追加消息走既有"输出区 appendLine + 活跃区 appendCommitted"双写范式；
 * 发用户输入、切规划模式、读上下文占用、弹菜单各自委托注入的入口（真实装配在接入主流程时完成）。
 */
public class TerminalUIController implements UIController {

    private final OutputPane output;
    private final RenderContext renderContext;
    private final Consumer<String> submitUserInput;
    private final Consumer<Boolean> planModeSetter;
    private final Supplier<ContextUsage> contextUsage;
    private final BiFunction<List<MenuEntry>, String, Integer> menu;

    public TerminalUIController(OutputPane output, RenderContext renderContext,
                                Consumer<String> submitUserInput, Consumer<Boolean> planModeSetter,
                                Supplier<ContextUsage> contextUsage,
                                BiFunction<List<MenuEntry>, String, Integer> menu) {
        this.output = output;
        this.renderContext = renderContext;
        this.submitUserInput = submitUserInput;
        this.planModeSetter = planModeSetter;
        this.contextUsage = contextUsage;
        this.menu = menu;
    }

    @Override
    public void appendSystemMessage(String text) {
        output.appendLine(text);
        renderContext.liveRenderer().appendCommitted(renderContext.screenWriter(), text);
    }

    @Override
    public void submitUserInput(String text) {
        submitUserInput.accept(text);
    }

    @Override
    public void setPlanMode(boolean enabled) {
        planModeSetter.accept(enabled);
    }

    @Override
    public ContextUsage contextUsage() {
        return contextUsage.get();
    }

    @Override
    public int selectMenu(List<MenuEntry> entries, String title) {
        return menu.apply(entries, title);
    }
}
