package com.acode.ui;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * 把输入框钉到屏幕底部：读输入前把光标下移到「底部状态区之上」，读完再挪回原处，
 * 让界面呈现为「对话内容在顶部、空档居中、输入框贴底」。
 *
 * <p>为什么需要它：JLine 把提示符画在**光标当前所在行**，而光标停在上一段内容的末尾，
 * 于是输入框紧贴内容、与钉底的页脚之间空出一大块。把光标先挪下去，提示符自然落到底部。
 *
 * <p><b>为什么跨轮挪光标是安全的</b>：JLine 每轮 {@code readLine} 都会新建一个
 * {@code Display}，把「当前光标处」当作原点，所以两次 {@code readLine} **之间**由外部
 * 挪动光标不会累积错位。{@code readLine} 进行中挪动才会失同步，故本类只在读输入前后动作。
 *
 * <p><b>为什么自己实现 CPR 而不直接用 {@code Terminal.getCursorPosition}</b>：JLine 那份实现
 * 内部是无超时的阻塞读，终端若定义了 {@code user6}/{@code user7} 却不回应，界面会永久卡死。
 * 这里用 {@link NonBlockingReader#read(long)} 限时读取，超时即放弃本轮定位。
 */
public final class BottomAnchor {

    /** CPR 超时；超时即放弃本轮定位，退回「输入框跟在内容后面」的老行为。 */
    static final long CPR_TIMEOUT_MS = 300;
    /** 读到这么多字节还没解析出 CPR 响应就放弃，避免长时间吞掉用户输入。 */
    private static final int CPR_MAX_BYTES = 24;

    private final Terminal terminal;

    public BottomAnchor(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * 提示符首行应落在第几行（**1 基**，与 CPR 报回来的口径一致）。
     * 全部按 1 基算是有意的：混用 0 基会整体差一行。
     *
     * @param height     终端总行数
     * @param footerRows 底部状态区占的行数（页脚钉在最后这么多行上）
     * @param promptRows 提示符占的行数（装饰行 + 输入行）
     */
    static int promptFirstRow(int height, int footerRows, int promptRows) {
        return height - footerRows - promptRows + 1;
    }

    /** 从 cursorRow 下移到 targetFirstRow 需要走的行数；光标已在目标处或更靠下时为 0。 */
    static int rowsToMove(int cursorRow, int targetFirstRow) {
        return Math.max(0, targetFirstRow - cursorRow);
    }

    /**
     * 把光标下移到「提示符首行」的位置，返回实际下移的行数（0 表示没动）。
     *
     * @return 下移行数，原样交给 {@link #unpin(int)} 回退
     */
    public int pin(int height, int footerRows, int promptRows) {
        Integer cursorRow = WindowsConsoleCursor.viewportRow();
        if (cursorRow == null) {
            cursorRow = queryCursorRow(terminal);
        }
        if (cursorRow == null) {
            return 0;
        }
        int moved = rowsToMove(cursorRow, promptFirstRow(height, footerRows, promptRows));
        if (moved > 0) {
            moveCursor(terminal, moved);
        }
        return moved;
    }

    /**
     * 回退光标到钉之前的位置。回车擦除提示符后，光标停在提示符块的**首行**（真机实测），
     * 所以上移量就是当初的下移量——多减一行会把内容顶到屏幕上方去。
     */
    public void unpin(int movedRows) {
        if (movedRows > 0) {
            moveCursor(terminal, -movedRows);
        }
    }

    static void moveCursor(Terminal terminal, int delta) {
        if (delta == 0) {
            return;
        }
        try {
            Writer out = terminal.writer();
            out.write("\033[" + Math.abs(delta) + (delta > 0 ? "B" : "A"));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 问终端光标在第几行。
     *
     * <p>请求经 {@code puts} 发出（terminfo 里 {@code u7} 的值是字面量 {@code "\E[6n"}，
     * 必须由 {@code Curses.tputs} 解释 {@code \E} 才是真正的 ESC 字节；直接 write 原文只会把这
     * 六个字符打到屏幕上）。
     *
     * @return 1 基行号；终端不支持、超时、或读到的不是 CPR 响应时返回 null
     */
    static Integer queryCursorRow(Terminal terminal) {
        if (terminal.getStringCapability(InfoCmp.Capability.user7) == null
                || terminal.getStringCapability(InfoCmp.Capability.user6) == null) {
            return null;
        }
        try {
            terminal.puts(InfoCmp.Capability.user7);
            terminal.flush();

            NonBlockingReader reader = terminal.reader();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + CPR_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                int c = reader.read(Math.max(1, deadline - System.currentTimeMillis()));
                if (c == NonBlockingReader.READ_EXPIRED || c < 0) {
                    break;
                }
                sb.append((char) c);
                // 响应必定以 ESC 开头；首字节不是它，说明读到的是用户敲的键，立刻收手，
                // 别把输入继续吞下去（窗口内至多损失这一次按键）。
                if (sb.length() == 1 && c != '\033') {
                    break;
                }
                Integer row = parseRow(sb);
                if (row != null) {
                    return row;
                }
                if (sb.length() > CPR_MAX_BYTES) {
                    break;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 从累积字节里找 {@code ESC [ 行 ; 列 R}，返回行号（1 基）；不是合法响应时返回 null。 */
    static Integer parseRow(CharSequence s) {
        String text = s.toString();
        int esc = text.indexOf('\033');
        if (esc < 0 || esc + 2 >= text.length() || text.charAt(esc + 1) != '[') {
            return null;
        }
        String body = text.substring(esc + 2);
        int semi = body.indexOf(';');
        if (semi <= 0) {
            return null;
        }
        if (body.indexOf('R', semi) < 0) {
            return null;
        }
        try {
            int row = Integer.parseInt(body.substring(0, semi).trim());
            // 行号必为正：0 或负数只可能来自畸形的响应，按「解析失败」处理，
            // 否则 rowsToMove 会把光标往反方向带出屏幕。
            return row > 0 ? row : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
