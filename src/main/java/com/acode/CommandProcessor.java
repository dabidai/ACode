package com.acode;

import com.acode.command.CommandDispatcher;
import com.acode.command.CommandRegistry;
import com.acode.command.CommandResult;
import com.acode.session.SessionManager;
import com.acode.ui.AcodeTerminal;
import com.acode.ui.BottomAnchor;
import com.acode.ui.InputPane;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.util.Objects;
import java.util.List;

/** 主循环：读一行 → 交给命令调度器 → 按返回结果决定去留；两种退出路径都关闭会话。 */
public class CommandProcessor {

    /**
     * 输入框装饰：模式行、上边线与输入标记属于 JLine 提示符；下边线和模型行
     * 属于底部状态区。较新 JLine 的 WINCH 路径会重建 Display 光标模型。
     * 未注入（测试路径）时主循环只画裸输入行、不做任何帧操作。
     */
    public interface InputFrame {
        /** 画等待帧：底部状态区（页脚）。 */
        void draw();

        /**
         * 完整输入提示符，可含 ANSI 与换行，每轮读取及 resize 时重建。
         * 默认返回裸输入提示符——测试路径的假实现不必关心装饰。
         */
        default String inputPrompt() {
            return InputPane.DEFAULT_PROMPT;
        }

        /**
         * 底部状态区占的行数，用来算输入框该沉到哪一行。默认 0（测试路径没有状态区，
         * 主循环据此跳过钉底）。
         */
        default int footerRows() {
            return 0;
        }

        /** 活动输入期间终端尺寸变化后，按新宽度重绘底部状态区。 */
        default void resize() {
        }

        /** Entire input frame is rendered in the terminal status area. */
        default boolean statusOwnedInput() {
            return false;
        }

        default String mode() {
            return "default";
        }

        default String footer() {
            return "";
        }

        /** Replays committed conversation lines after a terminal resize. */
        default void replayHistory() {
        }

        default List<String> historyLines() {
            return List.of();
        }

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
     * step，主循环自己收尾（输入行上方的装饰是提示符的一部分，随 JLine 一起消失，不留孤儿行）。
     * <p>每轮读输入前后用 {@link BottomAnchor} 把输入框钉到屏幕底部、读完再挪回来；两条退出
     * 路径都要回退，否则光标留在底部，后续输出会从那里往下写。
     */
    public void mainLoop() {
        InputPane input = new InputPane(tui.terminal(), InputPane.DEFAULT_PROMPT, registry);
        BottomAnchor anchor = new BottomAnchor(tui.terminal());
        if (!statusOwnedInput()) {
            drawFrame();
        }
        while (true) {
            String prompt = prompt();
            int pinned = statusOwnedInput() ? 0 : pin(anchor, prompt);
            String line;
            try {
                line = statusOwnedInput()
                        ? input.readLineFramed(inputFrame::mode, inputFrame::footer,
                                inputFrame::replayHistory, inputFrame::historyLines)
                        : inputFrame == null
                        ? input.readLine(prompt)
                        : input.readLine(this::prompt, inputFrame::resize);
            } catch (UserInterruptException | EndOfFileException e) {
                anchor.unpin(unpinDistance(pinned, input.wasResizedDuringLastRead()));
                if (inputFrame != null) {
                    inputFrame.erase();
                }
                sessionManager.closeSession();
                return;
            }
            anchor.unpin(unpinDistance(pinned, input.wasResizedDuringLastRead()));
            if (step(line) == CommandResult.EXIT) {
                sessionManager.closeSession();
                return;
            }
        }
    }

    /**
     * 本轮输入提示符：真实终端由输入帧提供模式、上边线和输入标记；无帧（测试路径）时
     * 使用裸提示符。任何异常的空值都降级为裸提示符。
     */
    String prompt() {
        String framedPrompt = inputFrame != null ? inputFrame.inputPrompt() : null;
        return framedPrompt == null || framedPrompt.isEmpty()
                ? InputPane.DEFAULT_PROMPT
                : framedPrompt;
    }

    /**
     * 把输入框钉到屏幕底部，返回下移行数（0 表示没动）。
     * <p>没有输入帧（测试路径）、没有页脚、或终端不回应光标查询时一律返回 0——
     * 退回「输入框跟在内容后面」的行为，功能不受影响。
     */
    private int pin(BottomAnchor anchor, String prompt) {
        if (inputFrame == null || tui == null) {
            return 0;
        }
        int footerRows = inputFrame.footerRows();
        if (footerRows <= 0) {
            return 0;
        }
        return anchor.pin(tui.height(), footerRows, displayRows(prompt));
    }

    /** 提示符占的显示行数：换行数 + 1。装饰行与输入行都由厂商保证不折行（宽度已截断）。 */
    static int displayRows(String prompt) {
        int rows = 1;
        for (int i = 0; i < prompt.length(); i++) {
            if (prompt.charAt(i) == '\n') {
                rows++;
            }
        }
        return rows;
    }

    /** resize 后终端已自行回流，旧高度下的回退距离不再有效；继续使用会把后续输出拉到错误行。 */
    static int unpinDistance(int pinned, boolean resizedDuringRead) {
        return resizedDuringRead ? 0 : pinned;
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
        if (inputFrame != null && !statusOwnedInput()) {
            inputFrame.draw();
        }
    }

    private boolean statusOwnedInput() {
        return inputFrame != null && inputFrame.statusOwnedInput();
    }

    /** 单行输入：原样交给调度器，返回值即主循环去留 */
    CommandResult handleLine(String line) {
        return Objects.requireNonNull(commandDispatcher, "命令调度器未注入").dispatch(line);
    }
}
