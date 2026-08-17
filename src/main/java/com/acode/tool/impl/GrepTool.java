package com.acode.tool.impl;

import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 按正则搜索文件内容：可限定基准目录与文件名过滤，返回命中的路径 + 行号 + 行内容。
 * 命中数超过上限时截断并附提示。
 */
public class GrepTool extends BaseTool {

    public static final int MAX_HITS = 500;

    public GrepTool() {
        super("Grep",
                "按正则搜索文件内容，返回命中文件的路径、行号与行内容。"
                        + "需要定位某个字符串/符号出现在哪些文件时用本工具；不要用 Bash 执行 grep/rg。"
                        + "pattern 为正则表达式；path 为搜索基准目录，缺省为工作目录；include 按文件名过滤，如 *.java。"
                        + "命中超 500 条截断并附提示。",
                Permission.READ);
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("pattern", ParamSpec.Type.STRING, "要匹配的正则表达式"),
                ParamSpec.optional("path", ParamSpec.Type.STRING,
                        "搜索基准目录，缺省为工作目录"),
                ParamSpec.optional("include", ParamSpec.Type.STRING,
                        "文件名 glob 过滤，如 *.java，缺省匹配全部文件"));
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(input.get("pattern").asText());
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("正则表达式错误：" + e.getDescription());
        }
        Path base = input.hasNonNull("path")
                ? context.resolve(input.get("path").asText())
                : context.workingDirectory();
        if (!Files.isDirectory(base)) {
            return ToolResult.failure("目录不存在：" + base);
        }
        PathMatcher nameMatcher = input.hasNonNull("include")
                ? FileSystems.getDefault().getPathMatcher("glob:" + input.get("include").asText())
                : null;

        List<String> hits = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(base)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (nameMatcher != null && !nameMatcher.matches(file.getFileName())) {
                    continue;
                }
                if (hits.size() >= MAX_HITS) {
                    truncated = true;
                    break;
                }
                collectHits(file, pattern, hits, MAX_HITS);
                if (hits.size() >= MAX_HITS) {
                    truncated = true;
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("遍历目录失败：" + base + "：" + e.getMessage());
        }

        String body = String.join("\n", hits);
        if (truncated) {
            body += "\n…（命中过多，已截断，仅显示前 " + MAX_HITS + " 条）";
        }
        return ToolResult.success(body).withDisplay(
                "返回 " + hits.size() + " 条命中" + (truncated ? "（已截断）" : ""));
    }

    private static void collectHits(Path file, Pattern pattern, List<String> hits, int max) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            Iterator<String> it = lines.iterator();
            int lineNo = 0;
            while (it.hasNext() && hits.size() < max) {
                lineNo++;
                String line = it.next();
                if (pattern.matcher(line).find()) {
                    hits.add(file + ":" + lineNo + ":" + line);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 跳过无法读取或解码的文件，不影响整体结果
        }
    }
}
