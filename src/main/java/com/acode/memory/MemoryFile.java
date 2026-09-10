package com.acode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条记忆：结构化头部（name / description / type）+ 正文（含 {@code **Why**：} 与
 * {@code **How to apply**：} 两行）。
 *
 * <p>文件名由头部推导为 {@code <type>-<name>.md}；{@code name} 同时兼作索引里的标题，
 * 因此被约束为短横线小写 slug（与仓库既有记忆文件的命名习惯一致）。
 */
public record MemoryFile(MemoryType type, String name, String description, String body) {

    /** name 兼作 slug：小写字母数字与短横线，首位必须是字母或数字 */
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private static final String FENCE = "---";
    private static final Pattern ENTRY = Pattern.compile("^([A-Za-z_]+):\\s*(.*)$");

    /** 推导文件名；name 非法时为 null（写入前会被 MemoryScope 再拒一次） */
    public String fileName() {
        return type.slug() + "-" + name + ".md";
    }

    /** 渲染为落盘文本：frontmatter + 空行 + 正文 */
    public static String render(MemoryFile memory) {
        return FENCE + "\n"
                + "name: " + memory.name() + "\n"
                + "description: " + memory.description() + "\n"
                + "type: " + memory.type().slug() + "\n"
                + FENCE + "\n\n"
                + (memory.body() == null ? "" : memory.body().strip()) + "\n";
    }

    /** 读取记忆文件；不可读返回空 */
    public static Optional<MemoryFile> read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 解析记忆文本；头部残缺、type 非四类之一、name 非法都返回空（调用方跳过该文件并告警） */
    public static Optional<MemoryFile> parse(String content) {
        if (content == null) {
            return Optional.empty();
        }
        List<String> lines = List.of(content.replace("\r\n", "\n").split("\n", -1));
        if (lines.isEmpty() || !FENCE.equals(lines.get(0).strip())) {
            return Optional.empty();
        }
        String name = null;
        String description = null;
        String typeSlug = null;
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (FENCE.equals(line)) {
                end = i;
                break;
            }
            Matcher m = ENTRY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            switch (m.group(1)) {
                case "name" -> name = m.group(2).strip();
                case "description" -> description = m.group(2).strip();
                case "type" -> typeSlug = m.group(2).strip();
                default -> { /* 未知字段忽略，便于手工维护的记忆文件多写几行 */ }
            }
        }
        if (end < 0 || name == null || description == null || typeSlug == null) {
            return Optional.empty();
        }
        MemoryType type = MemoryType.fromSlug(typeSlug);
        if (type == null) {
            return Optional.empty();
        }
        // 兼容手工按"文件名"写 name 的写法（name: project-deadline.md → deadline）
        name = normalizeName(name, type.slug());
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        String body = String.join("\n", new ArrayList<>(lines.subList(end + 1, lines.size()))).strip();
        return Optional.of(new MemoryFile(type, name, description, body));
    }

    private static String normalizeName(String value, String typeSlug) {
        String name = value.endsWith(".md") ? value.substring(0, value.length() - 3) : value;
        String prefix = typeSlug + "-";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }
}
