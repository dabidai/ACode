package com.acode.worktree;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Root-relative positive glob subset. No traversal, negation or implicit basename matching. */
public final class IncludeMatcher {
    private final List<Pattern> patterns = new ArrayList<>();
    public IncludeMatcher(List<String> lines) {
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("!") || line.contains("\\") || List.of(line.split("/")).contains(".."))
                throw new IllegalArgumentException(".worktreeinclude 不支持取反或路径穿越：" + line);
            if (line.startsWith("/")) line = line.substring(1);
            if (line.endsWith("/")) line += "**";
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '*') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '*') {
                        i++;
                        if (i + 1 < line.length() && line.charAt(i + 1) == '/') { i++; regex.append("(?:.*/)?"); }
                        else regex.append(".*");
                    } else regex.append("[^/]*");
                } else if (ch == '?') regex.append("[^/]");
                else regex.append(Pattern.quote(String.valueOf(ch)));
            }
            patterns.add(Pattern.compile(regex.append('$').toString()));
        }
    }
    public boolean matches(String path) { return patterns.stream().anyMatch(p -> p.matcher(path).matches()); }
}
