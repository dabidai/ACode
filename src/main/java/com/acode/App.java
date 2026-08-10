package com.acode;

import com.acode.ui.AcodeTerminal;
import com.acode.ui.CommandRouter;
import com.acode.ui.InputPane;
import com.acode.ui.OutputPane;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * ACode 入口。T1 解析启动参数；T9 起非 --resume 直接进入全屏 TUI；
 * T12 在此装配完整对话循环（Provider + 会话持久化）。
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static final String BANNER = """
             ___   ____    ___   ___   ____
            / _ \\ / ___|  / _ \\ / _ \\ |  _ \\
           | | | | |     | | | | | | || | | |
           | |_| | |___  | |_| | |_| || |_| |
            \\___/ \\____|  \\___/ \\___/ |____/
                          ACode v0.1.0
           """;

    public static void main(String[] args) {
        // Windows 默认按 GBK 写 stdout/stderr，强制 UTF-8，避免终端中文乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        boolean resume = hasResumeFlag(args);
        log.info("ACode 启动，resume={}", resume);
        if (resume) {
            System.out.println("[恢复会话模式] 将恢复最近一次会话（T12 实现）");
            return;
        }
        runTui();
    }

    private static boolean hasResumeFlag(String[] args) {
        for (String arg : args) {
            if ("--resume".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** T9 最小可用 TUI：全屏布局 + 命令分流 + 占位回复；T12 在此接入 Provider 与持久化。 */
    private static void runTui() {
        try (AcodeTerminal tui = AcodeTerminal.open()) {
            OutputPane output = new OutputPane();
            output.append(BANNER);
            output.appendLine("输入 /help 查看命令，/quit 退出");
            InputPane input = new InputPane(tui.terminal(), "> ");
            while (true) {
                tui.repaint(output);
                String line;
                try {
                    line = input.readLine();
                } catch (UserInterruptException | EndOfFileException e) {
                    return; // 空输入框 Ctrl+C / Ctrl+D → 退出，terminal 自动恢复
                }
                switch (CommandRouter.route(line)) {
                    case QUIT -> {
                        return;
                    }
                    case CLEAR -> {
                        output.clear();
                        output.appendLine("（已清空）");
                    }
                    case HELP -> output.append(CommandRouter.HELP_TEXT);
                    case SKIP -> {
                        // 空白输入，仅重绘
                    }
                    case CHAT -> {
                        output.appendLine("● " + line);
                        output.appendLine("● （回复将在 T12 接入 Provider 后显示）");
                    }
                }
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        }
    }
}
