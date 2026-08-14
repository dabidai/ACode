package com.acode.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;

/**
 * 终端生命周期壳：打开/关闭/尺寸/写入，只做主屏输出，不做整屏绘制。
 * 已提交内容追加进原生 scrollback（可划选复制）；底部活跃区重绘由
 * {@link LiveRegionRenderer} 负责，经此写入终端。
 */
public class AcodeTerminal implements AutoCloseable {

    private final Terminal terminal;

    private AcodeTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * 打开系统终端并进入 raw 模式。
     * 无可用终端（或 JLine 静默回退到不支持光标绘制的 dumb 终端）时抛 IllegalStateException。
     * 活跃区重绘依赖光标上移（cursor_up）与清到屏尾（clr_eos）能力，需一并检查。
     */
    public static AcodeTerminal open() {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(false)
                    .encoding("UTF-8")
                    .build();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("无法初始化终端（需在真实终端中运行）：" + e.getMessage(), e);
        }
        if (terminal.getHeight() <= 0
                || terminal.getStringCapability(InfoCmp.Capability.cursor_up) == null
                || terminal.getStringCapability(InfoCmp.Capability.clr_eos) == null) {
            closeQuietly(terminal);
            throw new IllegalStateException("未检测到支持活跃区绘制的终端（需光标上移/清屏能力），请在真实终端中运行 ACode");
        }
        terminal.enterRawMode();
        return new AcodeTerminal(terminal);
    }

    private static void closeQuietly(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException ignored) {
            // 尽力关闭
        }
    }

    public Terminal terminal() {
        return terminal;
    }

    public int height() {
        return terminal.getHeight();
    }

    public int width() {
        return terminal.getWidth();
    }

    public void write(String text) {
        terminal.writer().print(text);
    }

    public void flush() {
        terminal.writer().flush();
    }

    @Override
    public void close() {
        try {
            terminal.close();
        } catch (IOException e) {
            // 退出时尽力恢复终端；失败可忽略
        }
    }
}
