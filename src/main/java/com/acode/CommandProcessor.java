package com.acode;

import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.session.SessionManager;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.InputPane;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.util.Objects;

/** 主循环：读一行 → 交给命令调度器 → 按返回结果决定去留；两种退出路径都关闭会话。 */
public class CommandProcessor {

    /**
     * 输入框边框：等待输入时提示符**上方**一行模式行 + 一行分隔线，**下方**是底部常驻状态区
     * （分隔线 + 页脚）。由装配方注入；未注入（测试路径）时主循环不做任何帧操作。
     */
    public interface InputFrame {
        /** 画等待帧：模式行（仅内容变化时追加）+ 底部状态区。 */
        void draw();

        /** 收起底部状态区，把底部行交还给即将写入的输出。 */
        void erase();
    }

    private final AcodeTerminal tui;
    private final SessionManager sessionManager;
    /** 命令注册中心：供输入区 Tab 补全取候选（与调度器同一注册中心） */
    private final CommandRegistry registry;

    /** 命令调度器：由装配方注入（T12），注入前主循环不可用 */
    private CommandDispatcher commandDispatcher;

    /** 输入框边框（真实终端路径注入）：每轮输入前后由主循环统一擦画 */
    private InputFrame inputFrame;

    public CommandProcessor(AcodeTerminal tui, SessionManager sessionManager, CommandRegistry registry) {
        this.tui = tui;
        this.sessionManager = sessionManager;
        this.registry = registry;
    }

    /** 注入命令调度器：主循环的每一行输入都交给它执行 */
    public void setCommandDispatcher(CommandDispatcher dispatcher) {
        if (dispatcher != null) {
            this.commandDispatcher = dispatcher;
        }
    }

    /** 注入输入框边框；不注入则不画帧（测试路径）。 */
    public void setInputFrame(InputFrame inputFrame) {
        this.inputFrame = inputFrame;
    }

    /**
     * 主循环：帧的擦与画都由 {@link #step} 统一负责，而不是让交换/命令各自处理——擦（\033[J）
     * 与画（接着光标写帧）是一对必须配对的光标操作，分散到多处方容易漏。Ctrl+C / Ctrl+D 不经
     * step，主循环自己收尾（帧在提示符上方，只需擦掉 JLine 的地盘，不会留下孤儿行）。
     */
    public void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), ">*", registry);
        drawFrame();
        while (true) {
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
                if (inputFrame != null) {
                    inputFrame.erase();
                }
                sessionManager.closeSession();
                return;
            }
            if (step(line) == CommandResult.EXIT) {
                sessionManager.closeSession();
                return;
            }
        }
    }

    /**
     * 单行输入的完整处理：回车后先擦输入区残迹 → 派发（输出从此在干净区域追加）→ 回来重画帧。
     * <p>空行不产出一字：调度器对它直接返回 CONTINUE，擦画纯属白费（且每次空回车都会往回滚里
     * 多堆一组帧行）。退出路径也不再重画（随后就离开终端）。
     */
    CommandResult step(String line) {
        boolean framed = inputFrame != null && !line.isBlank();
        if (framed) {
            inputFrame.erase();
        }
        CommandResult result = handleLine(line);
        if (framed && result != CommandResult.EXIT) {
            drawFrame();
        }
        return result;
    }

    private void drawFrame() {
        if (inputFrame != null) {
            inputFrame.draw();
        }
    }

    /** 单行输入：原样交给调度器，返回值即主循环去留 */
    CommandResult handleLine(String line) {
        return Objects.requireNonNull(commandDispatcher, "命令调度器未注入").dispatch(line);
    }
}
