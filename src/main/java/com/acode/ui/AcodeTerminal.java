package com.acode.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Owns terminal setup and full-screen lifetime; redirected terminals use plain text. */
public class AcodeTerminal implements AutoCloseable {

    private final Terminal terminal;
    private ScreenRenderer screen;
    public ScreenRenderer screen() { return screen; }
    public boolean interactive() {
        return !terminal.getType().startsWith("dumb") && terminal.getHeight() > 0
                && terminal.getStringCapability(InfoCmp.Capability.cursor_address) != null;
    }
    public ScreenRenderer openScreen(OutputPane output) {
        if (!interactive()) return null;
        if (screen == null) {
            screen = new ScreenRenderer(terminal, output);
            screen.open();
        }
        return screen;
    }

    /** 包可见：测试用虚拟终端（TerminalBuilder + 固定尺寸）构造，生产路径只走 {@link #open()}。 */
    AcodeTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * 打开系统终端并进入 raw 模式。
     * 支持光标定位的交互终端使用全屏；重定向或 dumb 终端保留普通文本路径。
     * 输入、输出编码分别设置，避免 Windows 本地代码页覆盖通用编码。
     */
    public static AcodeTerminal open() {
        Terminal terminal;
        try {
            TerminalBuilder builder = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .encoding(StandardCharsets.UTF_8)
                    .stdinEncoding(StandardCharsets.UTF_8)
                    .stdoutEncoding(StandardCharsets.UTF_8)
                    .stderrEncoding(StandardCharsets.UTF_8);
            String type = statusBarSafeType();
            if (type != null) {
                builder.type(type);
            }
            terminal = builder.build();
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("无法初始化终端（需在真实终端中运行）：" + e.getMessage(), e);
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

    /**
     * Windows 上改用去掉了 {@code am} 的自定义 terminfo，返回该类型名；其余情形返回 null（用默认类型）。
     * <p>
     * 只在 Windows 且用户未显式指定终端类型（{@code TERM} 与 {@code org.jline.terminal.type} 皆空，
     * 与 {@code TerminalBuilder.computeType()} 的优先级一致）时注入。Linux 有系统 terminfo、
     * 终端本来就正常，不冒这个险；用户显式配了类型也说明他要自己掌控，不覆盖。
     * <p>
     * 能力表从 JLine 自带的 {@code windows-vtp.caps} 读入后只删 {@code am}，读不到就放弃注入、
     * 退回默认路径（页脚观感有缺陷但功能不受影响）。详见 {@link TerminalCaps}。
     */
    private static String statusBarSafeType() {
        if (!TerminalCaps.shouldUseWindowsType(System.getProperty("os.name"),
                System.getenv("TERM"), System.getProperty("org.jline.terminal.type"))) {
            return null;
        }
        String base = loadWindowsVtpCaps();
        if (base == null) {
            return null;
        }
        // JLine 3.30 keeps defaults separate from loaded caps. Register the
        // generated type as loaded so Windows never probes a missing infocmp.
        InfoCmp.setLoadedInfoCmp(TerminalCaps.customType(), TerminalCaps.withoutAutoRightMargin(base));
        return TerminalCaps.customType();
    }

    /** 读 JLine 自带的 windows-vtp 能力表原文；资源缺失时返回 null 由调用方降级。 */
    private static String loadWindowsVtpCaps() {
        try (InputStream in = AcodeTerminal.class.getResourceAsStream("/org/jline/utils/windows-vtp.caps")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
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
            try { if (screen != null) screen.close(); }
            finally { terminal.close(); }
        } catch (IOException e) {
            // 退出时尽力恢复终端；失败可忽略
        }
    }
}
