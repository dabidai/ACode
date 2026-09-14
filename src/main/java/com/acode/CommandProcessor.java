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
     * 输入框边框：等待输入时提示符上方是模式行与分隔线、下方是页脚两行。由装配方注入；
     * 未注入（测试路径）时主循环不做任何帧操作。
     */
    public interface InputFrame {
        /** 画等待帧；返回时光标落在提示符行，等待 JLine 在其上绘制提示符。 */
        void draw();

        /** 擦掉提示符下方的页脚，把那块地盘交还给即将写入的输出。 */
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
     * 与画（\033[3A 回到提示符行）是一对必须配对的光标操作，分散到多处方容易漏。
     */
    public void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), ">*", registry);
        drawFrame();
        while (true) {
            String line;
            try {
                line = input.readLine();
            } catch (UserInterruptException | EndOfFileException e) {
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
     * 单行输入的完整处理：回车后先擦页脚 → 派发（输出从此在干净区域追加）→ 回来重画帧。
     * <p>空行不产出一字：JLine 只擦了提示符行、页脚完好，原地等下一次输入即可；若照常擦画，
     * 反而会在上方再叠一组模式行与分隔线。退出路径也不再重画（随后就离开终端）。
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
