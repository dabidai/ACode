package com.acode.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指令文件 {@code @include} 展开器。
 *
 * <p>整行匹配 {@code @include <路径>}（行首允许空白，本行不得有其他内容），
 * 路径相对<em>被引文件所在目录</em>解析；被引文件内容原地替换该行。
 *
 * <p>三重约束：固定深度上限、已展开文件集合（canonical 真实路径，防重复展开与 A↔B 环）、
 * 来源边界（项目级来源的引用必须落在项目根内、用户级来源的引用必须落在用户主目录内）。
 * 任何跳过都只产出一行告警，不抛错、不中断。
 */
public final class InstructionExpander {

    /** 顶层指令文件为第 1 层，即最多再展开 4 层 */
    static final int MAX_DEPTH = 5;
    /** 单个被展开文件的字符数上限 */
    static final int MAX_FILE_CHARS = 64_000;
    static final String TRUNCATION_MARKER = "…（内容过长，已截断）";

    private static final Pattern INCLUDE_LINE = Pattern.compile("^\\s*@include\\s+(\\S+)\\s*$");

    private final List<String> warnings = new ArrayList<>();
    private final Set<Path> expanded = new LinkedHashSet<>();

    /**
     * 展开入口。
     *
     * @param file          顶层指令文件
     * @param boundary      该来源允许的路径边界（项目级为项目根、用户级为用户主目录）
     * @param boundaryLabel 越界告警里显示的边界名
     */
    public String expand(Path file, Path boundary, String boundaryLabel) {
        Path fileCanonical = canonical(file);
        if (fileCanonical == null) {
            return "";
        }
        String result = expandFile(fileCanonical, canonical(boundary), boundaryLabel, 1);
        return result == null ? "" : result;
    }

    /** 本次展开累计的告警行（供 UI 输出） */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private String expandFile(Path file, Path boundary, String boundaryLabel, int depth) {
        if (expanded.contains(file)) {
            return null;
        }
        String raw = readOrNull(file);
        if (raw == null) {
            warn("指令文件不可读，已跳过：" + file);
            return null;
        }
        expanded.add(file);
        var lines = new ArrayList<String>();
        for (String line : truncate(raw).split("\n", -1)) {
            Matcher m = INCLUDE_LINE.matcher(line);
            if (!m.matches()) {
                lines.add(line);
                continue;
            }
            String target = m.group(1);
            if (depth >= MAX_DEPTH) {
                warn("@include 深度超限，已跳过：" + target);
                continue;
            }
            Path resolved = resolveRelative(file.getParent(), target);
            Path canonicalTarget = resolved == null ? null : canonical(resolved);
            if (canonicalTarget == null || !Files.isRegularFile(canonicalTarget)
                    || !Files.isReadable(canonicalTarget)) {
                warn("@include 目标不存在或不可读，已跳过：" + target);
                continue;
            }
            if (!canonicalTarget.startsWith(boundary)) {
                warn("@include 超出" + boundaryLabel + "范围，已跳过：" + target);
                continue;
            }
            String included = expandFile(canonicalTarget, boundary, boundaryLabel, depth + 1);
            if (included != null) {
                lines.add(included);
            }
        }
        return String.join("\n", lines);
    }

    private String readOrNull(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String content) {
        if (content.length() <= MAX_FILE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_FILE_CHARS) + TRUNCATION_MARKER;
    }

    private static Path resolveRelative(Path base, String target) {
        if (base == null) {
            return null;
        }
        try {
            Path p = Path.of(target);
            return p.isAbsolute() ? p : base.resolve(p);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * canonical 真实路径；目标不存在时退化为「父目录 real path + 文件名」，父目录也不存在返回 null。
     * 口径与 {@code permission.PathSandbox} 一致——符号链接按真实路径判定，不能靠 {@code ..} 前缀比对绕过。
     */
    private static Path canonical(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            Path parent = absolute.getParent();
            if (parent == null) {
                return null;
            }
            try {
                return parent.toRealPath().resolve(absolute.getFileName());
            } catch (IOException e2) {
                return null;
            }
        }
    }

    private void warn(String message) {
        warnings.add(message);
    }
}
