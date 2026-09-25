package com.acode.ui;

/**
 * Windows 终端的 terminfo 修补：去掉 {@code am}（auto_right_margin）能力。
 * <p>
 * <b>为什么要撒这个谎</b>：JLine 的 {@code Display} 从行尾右边界移到下一行时，靠「在最后一列写一个
 * 空格触发终端自动折行，再用退格退回列 0」（{@code Display.java} 的 {@code rawPrint(' ')} +
 * {@code key_backspace} 分支）。Windows Terminal 的 pending-wrap 会被退格<b>取消</b>而不是折行，
 * 于是紧跟其后的那个字符落回原行末——底部状态区里表现为「分隔线与页脚糊成一行、页脚首字母丢失」。
 * <p>
 * 该分支的开关是 {@code Display.wrapAtEol}，取自 terminfo 布尔能力 {@code auto_right_margin}。
 * 去掉它之后 JLine 改走「行尾发 CR（取消 pending-wrap）+ 显式下移」——这正是本项目的
 * {@code appendCommitted}（行尾 {@code \r\n}）已在真机验证可靠的写法。
 * <p>
 * 全 JLine 只有两处消费该能力（{@code Display} 与 {@code LineReaderImpl.freshLine}），都有显式分支，
 * 无其他暗依赖。
 */
public final class TerminalCaps {

    /**
     * 自定义终端类型名。JLine 内置名（{@code windows-vtp} 等）在 {@code InfoCmp} 静态块里
     * {@code putIfAbsent} 预注册、无法覆盖，只能另起一个名字注册修补后的能力表。
     */
    private static final String CUSTOM_TYPE = "acode-vtp";

    private TerminalCaps() {
    }

    /** 修补后能力表要注册的终端类型名，交给 {@code TerminalBuilder.type(...)}。 */
    public static String customType() {
        return CUSTOM_TYPE;
    }

    /** {@code os.name} 是否为 Windows；形参化以便测试直接注入系统属性值。 */
    public static boolean isWindows(String osName) {
        return osName != null && osName.contains("Windows");
    }

    /** 空白 TERM 不代表用户选择了终端类型；Windows 启动脚本可能传入空值或空格。 */
    static boolean shouldUseWindowsType(String osName, String term, String explicitType) {
        return isWindows(osName) && (term == null || term.isBlank())
                && (explicitType == null || explicitType.isBlank());
    }

    /**
     * 去掉 caps 文本里的 {@code am} 条目，其余逐字节保留；不含 {@code am} 时原样返回（幂等）。
     * <p>
     * 按逗号切分逐个条目判断，只摘掉内容恰为 {@code am} 的那两个字符（比较时去空白，写回时保留原样），
     * 因此 {@code sam}、{@code msgr}、{@code amx=\E[...} 这类都不会被误伤。
     * <p>
     * <b>只摘字符、不删整条，是因为条目边界按逗号切、换行会落进它后面那个条目里</b>：本文件第二行
     * 实际切出来是 {@code \n\tam}（带着上一行的换行），整条删掉会把第三行的 {@code colors#256} 等
     * 并进 caps 首行——而 {@code InfoCmp.parseInfoCmp} 是<b>从第二行才开始读</b>的，并行等于丢掉整行
     * 能力。摘掉两个字符后该条目变成空白，对解析器无害（它的条目正则要求至少一个非逗号字符）。
     */
    public static String withoutAutoRightMargin(String baseCaps) {
        if (baseCaps == null || baseCaps.isEmpty()) {
            return baseCaps;
        }
        StringBuilder out = new StringBuilder(baseCaps.length());
        int i = 0;
        int n = baseCaps.length();
        while (i < n) {
            int comma = baseCaps.indexOf(',', i);
            int end = comma < 0 ? n : comma;
            String token = baseCaps.substring(i, end);
            int at = "am".equals(token.trim()) ? token.indexOf("am") : -1;
            if (at < 0) {
                out.append(token);
            } else {
                out.append(token, 0, at).append(token, at + 2, token.length());
            }
            if (comma < 0) {
                break;
            }
            out.append(',');
            i = comma + 1;
        }
        return out.toString();
    }
}
