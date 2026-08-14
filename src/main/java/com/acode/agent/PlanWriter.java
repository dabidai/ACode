package com.acode.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 计划落盘：把 Agent 交付的计划正文写入 {工作目录}/.acode/plans/plan-&lt;slug&gt;.md。
 * slug 由正文首行清洗生成（保留字母数字、其余转连字符、截断 40 字符），为空时兜底时间戳。
 */
public class PlanWriter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public Path savePlan(Path workingDir, String content) throws IOException {
        Path plansDir = workingDir.resolve(".acode/plans");
        Files.createDirectories(plansDir);
        Path file = plansDir.resolve("plan-" + slugify(content) + ".md");
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        return file;
    }

    /** 首行清洗生成 slug：字母数字保留、其余转连字符、截断 40；空结果兜底时间戳 */
    static String slugify(String content) {
        String firstLine = content == null ? "" : content.lines().findFirst().orElse("").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < firstLine.length() && sb.length() < 40; i++) {
            char c = firstLine.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        String slug = sb.toString().replaceFirst("^[-\\s]+", "").replaceFirst("[-\\s]+$", "");
        if (slug.isEmpty()) {
            slug = TIMESTAMP.format(LocalDateTime.now());
        }
        return slug;
    }
}
