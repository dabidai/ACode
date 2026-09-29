import com.acode.ui.AcodeTerminal;
import com.acode.ui.BottomAnchor;
import com.acode.ui.StatusBar;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 探针：定位并验证「终端缩放后输入框错乱」的修法。
 *
 * <p>四个模式**逐层加码**：
 * <ul>
 *   <li>{@code bare}  —— 纯 JLine：单行提示符，无页脚、无钉底。对照组；
 *   <li>{@code frame} —— 三行提示符 + {@code Status} 页脚，不钉底；
 *   <li>{@code full}  —— 三行提示符 + 页脚 + {@link BottomAnchor} 钉底（**已知会坏**，对照组）；
 *   <li>{@code fix}   —— 同 {@code full}，外加尺寸轮询线程，中途缩放手接管屏幕底部区域。
 * </ul>
 *
 * <h2>要验的机制</h2>
 *
 * 真机实测（2026-09-18）暴露的真因**不止一层**：
 * <ol>
 *   <li>提示符文本在 {@code readLine} 开始时按当时宽度烤死，缩窄后那条满宽分隔线折成两行——
 *       只重建文本能治这一层；
 *   <li>更深的：**中途缩放后，输入框根本不在我们算出来的位置**。JLine {@code Display} 的
 *       {@code cursorPos} 是相对它自己模型的偏移，{@code resize()} 只改行列数、不重算该偏移；
 *       而终端那边已经把缓冲重排过了。于是回车后光标落点与「提示符块首行」差出十几行
 *       （实测第2轮差 20 行、第4轮差 2 行），按公式定点擦除必然擦错地方。
 * </ol>
 *
 * 所以修法是**缩放那一刻把屏幕底部整个接管**：从提示符块顶清到屏尾、把 JLine 的 Display
 * 记账归零、按新几何重新定位再重绘，页脚同样处理。清到什么位置需要知道光标在哪，靠 CPR 问终端拿。
 *
 * <h2>为什么用轮询而不是听 SIGWINCH</h2>
 *
 * {@code Terminal.handle(WINCH, h)} 是 {@code Map.put}，{@code readLine} 进入时会用 JLine 自己的
 * 处理器把我们顶掉（{@code LineReaderImpl:659}），返回时才还原（{@code :785}）——
 * **readLine 期间我们的处理器收不到信号**。
 *
 * <p>运行：
 * <pre>java -cp target/acode.jar probe/ResizeProbe.java fix</pre>
 *
 * <p>诊断**攒到退出后统一打印**——中途打印会把光标推下去，测出来的几何就不作数了。
 */
public class ResizeProbe {

    private static final int STATUS_ROWS = 2;
    /** 多行提示符的行数（模式行 + 分隔线 + 输入行）。 */
    private static final int PROMPT_ROWS = 3;
    private static final long CPR_TIMEOUT_MS = 300;
    /** 轮询间隔。够快能跟上拖拽，够慢不至于空转烧 CPU。 */
    private static final long POLL_MS = 120;

    private static final List<String> LOG = new ArrayList<>();
    private static final AtomicInteger WINCH = new AtomicInteger();
    private static final AtomicInteger REPAIRED = new AtomicInteger();
    private static final AtomicInteger REPAIR_SKIPPED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "fix";
        if (mode.equals("fix")) {
            System.err.println("fix 模式已废弃：活动 readLine 期间 ANSI CPR 会污染用户输入。请直接运行最新 ACode 验证 resize。");
            return;
        }
        boolean useStatus = !mode.equals("bare");
        boolean useAnchor = mode.equals("full") || mode.equals("fix");
        boolean multiPrompt = !mode.equals("bare");
        boolean useFix = mode.equals("fix");
        // bare 模式提示符只有 1 行（"> "），其余 3 行
        int promptRows = multiPrompt ? PROMPT_ROWS : 1;
        IntFunction<String> promptBuilder = w -> buildPrompt(w, multiPrompt);

        try (AcodeTerminal tui = AcodeTerminal.open()) {
            Terminal terminal = tui.terminal();
            Writer out = terminal.writer();

            banner(out, mode);

            try {
                terminal.handle(Terminal.Signal.WINCH, s -> WINCH.incrementAndGet());
            } catch (RuntimeException e) {
                LOG.add("注册 WINCH 处理器失败：" + e);
            }

            Status status = null;
            if (useStatus) {
                status = Status.getStatus(terminal, true);
                if (status == null) {
                    LOG.add("!! 拿不到 Status，frame/full/fix 模式无意义");
                    return;
                }
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("resize-probe")
                    .option(LineReader.Option.ERASE_LINE_ON_FINISH, true)
                    .build();

            BottomAnchor anchor = useAnchor ? new BottomAnchor(terminal) : null;
            AtomicBoolean stopWatch = new AtomicBoolean();
            boolean watcherUp = false;
            if (useFix) {
                watcherUp = startPromptWatch(terminal, reader, status, promptRows, promptBuilder, stopWatch);
            }

            LOG.add("模式=" + mode + "｜页脚=" + useStatus + "｜钉底=" + useAnchor
                    + "｜多行提示符=" + multiPrompt + "｜尺寸轮询=" + watcherUp);
            LOG.add("起始尺寸 " + tui.width() + "x" + tui.height());
            LOG.add("── 每轮诊断 ──");

            int round = 0;
            while (true) {
                round++;
                int h = tui.height();
                int w = tui.width();
                if (status != null) {
                    status.update(footerLines(w));
                }

                int scrollRegion = status == null ? -1 : fieldInt(status, "scrollRegion");
                Integer cpr = queryCursorRow(terminal);

                int moved = anchor == null ? 0 : anchor.pin(h, STATUS_ROWS, promptRows);

                LOG.add("[第" + round + "轮] 终端 " + w + "x" + h
                        + "｜scrollRegion=" + scrollRegion
                        + "｜CPR=" + (cpr == null ? "失败" : String.valueOf(cpr))
                        + "｜下移=" + moved
                        + "｜WINCH=" + WINCH.get()
                        + "｜修复=" + REPAIRED.get());

                String line;
                try {
                    line = reader.readLine(promptBuilder.apply(w));
                } catch (UserInterruptException | EndOfFileException e) {
                    cleanBlock(out, promptRows);
                    unpin(out, moved);
                    break;
                }

                // 先确认「回车后光标落在提示符块首行」这条不变量，再定点擦块。
                // **中途缩放过的那几轮它必然不成立**——那正是本次要修的东西。
                Integer afterRow = queryCursorRow(terminal);
                LOG.add("[第" + round + "轮] 回车后 CPR=" + (afterRow == null ? "失败" : afterRow)
                        + "（提示符块首行应为 " + (h - STATUS_ROWS - promptRows + 1) + "）");
                cleanBlock(out, promptRows);
                unpin(out, moved);

                if (line == null || line.trim().equals("/quit")) {
                    break;
                }
                writeLn(out, "● 你输入了：" + line);
            }

            stopWatch.set(true);
            if (status != null) {
                status.close();
                clearFooter(terminal, tui.height());
            }
        }

        System.out.println();
        System.out.println("== 汇总 ==");
        System.out.println("WINCH 处理器累计触发 " + WINCH.get() + " 次");
        System.out.println("缩放接管成功 " + REPAIRED.get() + " 次，跳过 " + REPAIR_SKIPPED.get() + " 次");
        for (String l : LOG) {
            System.out.println(l);
        }
    }

    // ─────────────────────────── 缩放时接管屏幕底部 ───────────────────────────

    /**
     * 起一个守护线程盯着终端尺寸；尺寸一变且**正在等输入**时，接管屏幕底部区域重修一遍。
     *
     * <p>为什么不能只重建提示符文本：中途缩放后输入框已经不在原位（JLine 的 {@code cursorPos}
     * 是相对偏移、{@code resize()} 不重算它，而终端那边已经重排过缓冲），只换文本重绘会画在错位的
     * 坐标上。所以必须：先问终端「光标现在在哪」（CPR），从提示符块顶清到屏尾，把 JLine 的记账
     * 归零，再按新几何定位重绘。
     *
     * @return 线程是否成功起来（反射不可用时返回 false，调用方按老行为降级）
     */
    private static boolean startPromptWatch(Terminal terminal, LineReader reader, Status status,
                                            int promptRows, IntFunction<String> promptBuilder,
                                            AtomicBoolean stop) {
        try {
            Refs.open();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.add("!! 反射拿不到 JLine 内部（prompt/display/redisplay），无法中途接管："
                    + e);
            return false;
        }

        Thread t = new Thread(() -> {
            Size last = terminal.getSize();
            while (!stop.get()) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                Size now = terminal.getSize();
                if (now.equals(last)) {
                    continue;
                }
                last = now;
                // 不在等输入就不用管：下一次 readLine 本来就会按新宽度重建提示符
                if (!reader.isReading()) {
                    REPAIR_SKIPPED.incrementAndGet();
                    continue;
                }
                try {
                    synchronized (reader) {
                        takeOverBottom(terminal, reader, status, promptRows, promptBuilder, now);
                    }
                    REPAIRED.incrementAndGet();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    REPAIR_SKIPPED.incrementAndGet();
                    LOG.add("[接管] 失败：" + e);
                }
            }
        }, "prompt-watch");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * 接管屏幕底部：清掉错位的旧框与页脚 → 把 JLine 记账归零 → 按新几何重新定位重绘。
     *
     * <p>清的理由：终端重排过缓冲，旧框已经不在我们算的位置，只能靠 CPR 问出光标实际在哪，
     * 再从那里往上一整块清到屏尾。反正那片区域（空档 + 输入框 + 页脚）本来就全是我们的。
     */
    private static void takeOverBottom(Terminal terminal, LineReader reader, Status status,
                                       int promptRows, IntFunction<String> promptBuilder, Size now)
            throws ReflectiveOperationException {
        Writer out = terminal.writer();
        Integer cur = queryCursorRow(terminal);
        LOG.add("[接管] " + now.getColumns() + "x" + now.getRows()
                + " → 现场 CPR=" + (cur == null ? "失败" : cur)
                + "｜reader.Display " + Refs.dump(Refs.readerDisplay(reader))
                + "｜status.Display " + Refs.dump(Refs.statusDisplay(status)));

        // 1. 光标在提示符块的**最后一行**（输入行）上，故块顶 = 当前行 - (行数-1)。
        //    清到屏尾会把旧框、空档、页脚一起抹掉——那片本来全是我们的地盘。
        int blockTop = cur == null ? -1 : Math.max(1, cur - promptRows + 1);
        if (blockTop > 0 && cur != null) {
            moveCursor(out, blockTop - cur);
            terminal.puts(InfoCmp.Capability.carriage_return);
            terminal.puts(InfoCmp.Capability.clr_eos);
            terminal.flush();
        }

        // 2. 定位到新几何下提示符块的首行
        int target = now.getRows() - STATUS_ROWS - promptRows + 1;
        Integer at = queryCursorRow(terminal);
        if (at != null && target > at) {
            moveCursor(out, target - at);
        }

        // 3. 把 JLine 的记账归零到「当前光标处」，否则它会按旧偏移重绘
        Refs.reset(Refs.readerDisplay(reader));

        // 4. 换成本宽度下的提示符并重绘
        Refs.prompt(reader, AttributedString.fromAnsi(promptBuilder.apply(now.getColumns())));
        Refs.redisplay(reader);

        // 5. 页脚也重画（它的旧行刚被清掉了，记账同样归零）
        if (status != null) {
            Refs.reset(Refs.statusDisplay(status));
            status.update(footerLines(now.getColumns()));
        }
        LOG.add("[接管] 完成：块首行目标 " + target + "，清到屏尾前光标在第 " + blockTop + " 行");
    }

    /** JLine 内部反射句柄，一次打开反复用；拿不到就整体降级。 */
    private static final class Refs {
        private static Field promptField;
        private static Method redisplayMethod;
        private static Field readerDisplayField;
        private static Field statusDisplayField;
        private static Field oldLinesField;
        private static Field cursorPosField;
        private static Field rowsField;
        private static Field columnsField;

        static void open() throws ReflectiveOperationException {
            promptField = accessible(LineReaderImpl.class.getDeclaredField("prompt"));
            redisplayMethod = LineReaderImpl.class.getDeclaredMethod("redisplay", boolean.class);
            redisplayMethod.setAccessible(true);
            readerDisplayField = accessible(LineReaderImpl.class.getDeclaredField("display"));
            statusDisplayField = accessible(Status.class.getDeclaredField("display"));
            oldLinesField = accessible(org.jline.utils.Display.class.getDeclaredField("oldLines"));
            cursorPosField = accessible(org.jline.utils.Display.class.getDeclaredField("cursorPos"));
            rowsField = accessible(org.jline.utils.Display.class.getDeclaredField("rows"));
            columnsField = accessible(org.jline.utils.Display.class.getDeclaredField("columns"));
        }

        private static Field accessible(Field f) {
            f.setAccessible(true);
            return f;
        }

        static Object readerDisplay(LineReader reader) throws ReflectiveOperationException {
            return readerDisplayField.get(reader);
        }

        static Object statusDisplay(Status status) throws ReflectiveOperationException {
            return status == null ? null : statusDisplayField.get(status);
        }

        @SuppressWarnings("unchecked")
        static void reset(Object display) throws ReflectiveOperationException {
            if (display == null) {
                return;
            }
            ((List<Object>) oldLinesField.get(display)).clear();
            cursorPosField.setInt(display, 0);
        }

        static void prompt(LineReader reader, AttributedString prompt)
                throws ReflectiveOperationException {
            promptField.set(reader, prompt);
        }

        static void redisplay(LineReader reader) throws ReflectiveOperationException {
            redisplayMethod.invoke(reader, true);
        }

        static String dump(Object display) {
            if (display == null) {
                return "n/a";
            }
            try {
                List<?> old = (List<?>) oldLinesField.get(display);
                return "cursorPos=" + cursorPosField.getInt(display)
                        + ",oldLines=" + (old == null ? -1 : old.size())
                        + "," + columnsField.getInt(display) + "x" + rowsField.getInt(display);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "读不到";
            }
        }
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private static void banner(Writer out, String mode) throws IOException {
        writeLn(out, "== ResizeProbe 模式 " + mode + " ==");
        writeLn(out, "请按顺序做，每步做完按回车继续：");
        writeLn(out, "  ① 不打字直接回车（基线）");
        writeLn(out, "  ② 把窗口拖小，**先别回车**，看输入框有没有跟着重新落到底部，再回车");
        writeLn(out, "  ③ 不拖窗口，直接回车");
        writeLn(out, "  ④ 把窗口拖大，**先别回车**，看输入框，再回车");
        writeLn(out, "  ⑤ 输入 /quit 结束");
        writeLn(out, "");
        out.flush();
    }

    private static List<AttributedString> footerLines(int width) {
        return List.of(
                AttributedString.fromAnsi(StatusBar.divider(width)),
                AttributedString.fromAnsi(StatusBar.infoLine(
                        "probe-model", 0.012, System.getProperty("user.dir"), width)));
    }

    /** 提示符：bare 模式单行，其余三行（模式行 + 分隔线 + 输入行），与 ACode 形状一致。 */
    private static String buildPrompt(int width, boolean multi) {
        if (!multi) {
            return "> ";
        }
        return StatusBar.modeLine("default", width) + "\n" + StatusBar.divider(width) + "\n> ";
    }

    private static void unpin(Writer out, int moved) {
        if (moved > 0) {
            moveCursor(out, -moved);
        }
    }

    /**
     * 定点擦掉提示符块占的那几行。
     *
     * <p>{@code ERASE_LINE_ON_FINISH} 是把 {@code prompt} 临时置空再重绘、让整块塌成一行空白；
     * 对多行提示符它会漏擦，留在屏上的正是块中间那条分隔线，而且每轮回车漏一条、越积越多。
     *
     * <p>前提：光标停在块的**首行**。未缩放的轮次实测吻合；缩放过的轮次由接管逻辑保证。
     */
    private static void cleanBlock(Writer out, int rows) {
        try {
            for (int i = 0; i < rows; i++) {
                out.write("\r\033[K");
                if (i < rows - 1) {
                    out.write("\033[B");
                }
            }
            for (int i = 0; i < rows - 1; i++) {
                out.write("\033[A");
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void moveCursor(Writer out, int delta) {
        if (delta == 0) {
            return;
        }
        try {
            out.write("\033[" + Math.abs(delta) + (delta > 0 ? "B" : "A"));
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

    /** {@code Status.close()} 不清屏上已画的内容，退出时手工擦掉页脚两行。 */
    private static void clearFooter(Terminal terminal, int height) {
        terminal.puts(InfoCmp.Capability.save_cursor);
        terminal.puts(InfoCmp.Capability.cursor_address, Math.max(0, height - STATUS_ROWS), 0);
        terminal.puts(InfoCmp.Capability.clr_eos);
        terminal.puts(InfoCmp.Capability.restore_cursor);
        terminal.flush();
    }

    private static int fieldInt(Object owner, String name) {
        try {
            return field(owner, name).getInt(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Class<?> c = owner.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ─────────────────────────── CPR ───────────────────────────

    /** 问终端光标在第几行；带超时，理由见 {@code BottomAnchor}。 */
    private static Integer queryCursorRow(Terminal terminal) {
        if (terminal.getStringCapability(InfoCmp.Capability.user7) == null
                || terminal.getStringCapability(InfoCmp.Capability.user6) == null) {
            return null;
        }
        try {
            terminal.puts(InfoCmp.Capability.user7);
            terminal.flush();
            NonBlockingReader r = terminal.reader();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + CPR_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                int c = r.read(Math.max(1, deadline - System.currentTimeMillis()));
                if (c == NonBlockingReader.READ_EXPIRED || c < 0) {
                    break;
                }
                sb.append((char) c);
                if (sb.length() == 1 && c != '\033') {
                    break;
                }
                Integer row = parseCpr(sb);
                if (row != null) {
                    return row;
                }
                if (sb.length() > 24) {
                    break;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Integer parseCpr(CharSequence s) {
        String text = s.toString();
        int esc = text.indexOf('\033');
        if (esc < 0 || esc + 2 >= text.length() || text.charAt(esc + 1) != '[') {
            return null;
        }
        String body = text.substring(esc + 2);
        int semi = body.indexOf(';');
        if (semi <= 0 || body.indexOf('R', semi) < 0) {
            return null;
        }
        try {
            int row = Integer.parseInt(body.substring(0, semi).trim());
            return row > 0 ? row : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
