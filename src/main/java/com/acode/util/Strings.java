package com.acode.util;

import java.util.ArrayList;
import java.util.List;

/** 通用字符串工具。 */
public final class Strings {

    private Strings() {
    }

    /**
     * 按行拆分：每段去掉行尾 \r；末尾换行不产生多余空段；保留中间空行；纯空串返回空列表。
     * 收敛了 EditFileTool/WriteFileTool 与 StreamPrinter 三处各自实现的同一逻辑。
     */
    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i).replace("\r", ""));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
