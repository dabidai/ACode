import com.acode.ui.AcodeTerminal;
import com.acode.ui.StatusBar;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 探针：验证「把输入框推到屏幕底部」的机制是否在真机上成立。
 *
 * <p>不碰 ACode 的任何界面代码，只复现机制本身：
 * <ol>
 *   <li>用 CPR（{@code \033[6n}）问终端「光标现在在第几行」，带超时；</li>
 *   <li>把光标下移到「滚动区底行 − 提示符高度 + 1」，让 JLine 在那里画多行提示符；</li>
 *   <li>回车后光标停在提示符块的最底行，再手动上移回退，使后续内容接在上一段之后，
 *       而不是掉到空档下面。</li>
 * </ol>
 *
 * <p>运行：
 * <pre>java -cp target/acode.jar probe/CursorProbe.java</pre>
 *
 * <p>诊断**攒到退出后统一打印**——若在每轮中途打印，那些行会把光标推下去，测出来的
 * 几何就不作数了。输入 {@code /quit} 结束。
 */
public class CursorProbe {

    /** 底部状态区行数（分隔线 + 页脚），与 ACode 一致。 */
    private static final int STATUS_ROWS = 2;
    /** 提示符高度（模式行 + 分隔线 + 输入行）；三段都按宽度截断过，故各占一行。 */
    private static final int PROMPT_ROWS = 3;
    /** CPR 超时；超时即放弃本轮定位（退回「提示符跟在内容后面」的老行为）。 */
    private static final long CPR_TIMEOUT_MS = 300;

    private static final List<String> LOG = new ArrayList<>();
    private static int cprOk;
    private static int cprFail;
    private static int moved;
    private static int notMoved;

    public static void main(String[] args) throws Exception {
        try (AcodeTerminal tui = AcodeTerminal.open()) {
            Terminal terminal = tui.terminal();
            LOG.add("终端类型：" + terminal.getType() + "，尺寸 " + tui.width() + "x" + tui.height());

            Status status = Status.getStatus(terminal, true);
            if (status == null) {
                LOG.add("!! 拿不到 Status 状态区，页脚机制不成立，后续结论无意义");
                return;
            }
            renderFooter(status, tui.width(), tui.height());
            LOG.add("已建立底部状态区（" + STATUS_ROWS + " 行）；1 基行号下，滚动区底行 = 高度 - "
                    + STATUS_ROWS + " = " + (tui.height() - STATUS_ROWS)
                    + "，提示符首行应为 " + (tui.height() - STATUS_ROWS - PROMPT_ROWS + 1));
            LOG.add("── 以下是每轮诊断 ──");

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("probe")
                    .option(LineReader.Option.ERASE_LINE_ON_FINISH, true)
                    .build();

            Writer out = terminal.writer();
            int round = 0;

            while (true) {
                round++;
                // 每轮重画页脚：与 ACode 的 drawFrame 一致，也让 Status 的滚动区跟着尺寸走
                renderFooter(status, tui.width(), tui.height());

                Integer cursorRow = queryCursorRow(terminal, CPR_TIMEOUT_MS);
                int movedBy = reposition(out, tui.height(), cursorRow, round);

                String line;
                try {
                    line = reader.readLine(buildPrompt(tui.width()));
                } catch (UserInterruptException | EndOfFileException e) {
                    writeLn(out, "（中断退出）");
                    if (movedBy > 0) {
                        moveCursor(out, -movedBy);
                    }
                    break;
                }

                // 实测：回车擦除后光标停在提示符块的**首行**（不是底行），故上移量就是下移量
                if (movedBy > 0) {
                    moveCursor(out, -movedBy);
                    LOG.add("[第" + round + "轮] 回车上移 " + movedBy + " 行回退");
                }

                if (line == null || line.trim().equals("/quit")) {
                    break;
                }
                writeLn(out, "● 输入：" + line);
            }

            status.close();
            clearFooter(terminal, tui.height());
        }

        // 终端已关闭，这时打印不会干扰界面
        System.out.println();
        System.out.println("== 汇总 ==");
        System.out.println("CPR 成功 " + cprOk + " 次，失败/超时 " + cprFail + " 次");
        System.out.println("实际下移 " + moved + " 次，无需下移 " + notMoved + " 次");
        System.out.println();
        for (String l : LOG) {
            System.out.println(l);
        }
    }

    /**
     * 擦掉页脚那两行。{@code Status.close()} 只把滚动区恢复成整屏，**不清屏上已画的内容**，
     * 所以退出后页脚会以残行的形式留在屏幕底部。
     *
     * <p>用 save/restore 包住绝对定位，与 JLine {@code Status} 内部的做法一致：定位到页脚首行
     * → 清到屏尾 → 光标还原到内容末尾（shell 提示符就会接在那里）。
     * {@code cursor_address} 的参数是 0 基，故传 {@code height - STATUS_ROWS}。
     */
    private static void clearFooter(Terminal terminal, int height) {
        terminal.puts(InfoCmp.Capability.save_cursor);
        terminal.puts(InfoCmp.Capability.cursor_address, height - STATUS_ROWS, 0);
        terminal.puts(InfoCmp.Capability.clr_eos);
        terminal.puts(InfoCmp.Capability.restore_cursor);
        terminal.flush();
    }

    private static int lastHeight = -1;

    /** 页脚：整行分隔线 + 一行状态（模拟 ACode 的页脚）。 */
    private static void renderFooter(Status status, int width, int height) {
        if (height != lastHeight) {
            // 尺寸变了。Status 不自己感知：display.rows 只在 resize() 时更新，而 update() 会拿
            // 缓存的 scrollRegion 当锚点做绝对定位——旧锚点在缩小后的屏幕上是屏幕外的行，
            // 于是画花。先 resize() 让 display 跟上，再 reset() 把锚点重归到「整屏」这个已知状态。
            status.resize();
            status.reset();
            lastHeight = height;
        }
        status.update(List.of(
                AttributedString.fromAnsi(StatusBar.divider(width)),
                AttributedString.fromAnsi("probe · ctx ▓░░░░░░░░░ 1.0% · " + System.getProperty("user.dir"))));
    }

    /** 提示符：模式行 + 分隔线 + 输入行，直接用 ACode 的 StatusBar 生成，形状与真机一致。 */
    private static String buildPrompt(int width) {
        return StatusBar.modeLine("default", width) + "\n" + StatusBar.divider(width) + "\n> ";
    }

    /**
     * 把光标下移到「滚动区底行 − 提示符高度 + 1」，返回实际下移的行数（0 表示没动）。
     *
     * <p><b>全程按 1 基行号</b>：CPR 报回来的就是 1 基，混用 0 基会整体差一行。
     * 高度 H 的终端里，状态区占最后 {@value #STATUS_ROWS} 行（第 H-1、H 行），
     * 滚动区底行是第 {@code H - STATUS_ROWS} 行——与 JLine {@code Status} 内部
     * 的 {@code rows - 1 - lines.size()}（0 基）是同一个位置。
     */
    private static int reposition(Writer out, int height, Integer cursorRow, int round) {
        int regionBottom = height - STATUS_ROWS;
        int targetFirstRow = regionBottom - PROMPT_ROWS + 1;
        if (cursorRow == null) {
            LOG.add("[第" + round + "轮] CPR 失败 → 本轮不定位（提示符将跟在内容后面）");
            return 0;
        }
        int delta = targetFirstRow - cursorRow;
        if (delta <= 0) {
            notMoved++;
            LOG.add("[第" + round + "轮] 光标在第 " + cursorRow + " 行，目标首行 " + targetFirstRow
                    + " → 内容已到底，不下移");
            return 0;
        }
        moved++;
        moveCursor(out, delta);
        LOG.add("[第" + round + "轮] 光标第 " + cursorRow + " 行 → 下移 " + delta + " 行，提示符画在 "
                + targetFirstRow + "~" + regionBottom + " 行（滚动区底行 " + regionBottom + "）");
        return delta;
    }

    private static void moveCursor(Writer out, int delta) {
        try {
            if (delta > 0) {
                out.write("\033[" + delta + "B");
            } else if (delta < 0) {
                out.write("\033[" + (-delta) + "A");
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeLn(Writer out, String text) {
        try {
            out.write(text + "\r\n");
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 问终端光标在哪一行。带超时——JLine 自带的 {@code CursorSupport.getCursorPosition}
     * 是无超时阻塞读，终端不回应就永久卡死，所以自己实现。
     *
     * @return 1 基行号；超时、终端不支持、或读到非 CPR 响应时返回 null
     */
    private static Integer queryCursorRow(Terminal terminal, long timeoutMs) {
        if (terminal.getStringCapability(InfoCmp.Capability.user7) == null
                || terminal.getStringCapability(InfoCmp.Capability.user6) == null) {
            LOG.add("terminfo 缺 user6/user7，无法查光标位置");
            return null;
        }
        try {
            // 必须走 puts/Curses.tputs：terminfo 里 u7 的值是字面量 "\E[6n"，
            // 需要解释 \E 才是真正的 ESC 字节。直接 write 原文只会把这六个字符打到屏幕上。
            terminal.puts(InfoCmp.Capability.user7);
            terminal.flush();

            NonBlockingReader r = terminal.reader();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int c = r.read(Math.max(1, deadline - System.currentTimeMillis()));
                if (c == NonBlockingReader.READ_EXPIRED || c < 0) {
                    break;
                }
                sb.append((char) c);
                Integer row = parseCpr(sb);
                if (row != null) {
                    cprOk++;
                    return row;
                }
                if (sb.length() > 24) {
                    break; // 明显不是 CPR 响应（可能是用户敲的键）
                }
            }
            cprFail++;
            LOG.add("CPR 未拿到行号，读到：" + describe(sb));
            return null;
        } catch (IOException e) {
            cprFail++;
            LOG.add("CPR IO 异常：" + e);
            return null;
        }
    }

    /** 从累积字节里找 {@code ESC [ 行 ; 列 R}，返回行号（1 基）。 */
    private static Integer parseCpr(StringBuilder sb) {
        String s = sb.toString();
        int esc = s.indexOf('\033');
        if (esc < 0 || esc + 2 >= s.length() || s.charAt(esc + 1) != '[') {
            return null;
        }
        String body = s.substring(esc + 2);
        int semi = body.indexOf(';');
        if (semi <= 0) {
            return null;
        }
        if (body.indexOf('R', semi) < 0) {
            return null;
        }
        try {
            return Integer.parseInt(body.substring(0, semi).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String describe(StringBuilder sb) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            b.append(c < 32 ? "<" + (int) c + ">" : c);
        }
        return b.toString();
    }
}
