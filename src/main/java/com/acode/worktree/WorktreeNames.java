package com.acode.worktree;

import java.util.Locale;

public final class WorktreeNames {
    private WorktreeNames() {}

    public static String validate(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称不能为空");
        if (name.length() > 64) throw new IllegalArgumentException("名称过长（最多 64 个字符）");
        for (String part : name.split("/", -1)) {
            if (part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("名称不能包含 \".\" 或 \"..\" 段落");
            if (!part.matches("[a-zA-Z0-9._-]+") || part.startsWith(".") || part.endsWith(".")
                    || part.contains("..") || part.toLowerCase(Locale.ROOT).endsWith(".lock") || device(part))
                throw new IllegalArgumentException("名称包含非法路径或 Git 分支段：" + part);
        }
        return name.replace('/', '+');
    }

    public static boolean device(String part) {
        return part.toUpperCase(Locale.ROOT).split("\\.", 2)[0]
                .matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }

    public static boolean temporary(String name) {
        return name.matches("agent-a[0-9a-f]{7}|wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+");
    }
}
