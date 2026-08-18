package com.acode.tool.impl;

import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 按 glob 模式递归匹配文件路径（支持 **），返回匹配的路径列表。
 * 结果超过上限时截断并附提示。
 */
public class GlobTool extends BaseTool {

    public static final int MAX_RESULTS = 200;

    public GlobTool() {
        super("Glob",
                "按 glob 模式递归匹配文件路径（支持 **），返回匹配到的路径列表。"
                        + "文件查找用本工具，优先于 Bash 的 find/ls；需要按文件名查找或列出目录内容时用它。"
                        + "pattern 示例 **/*.java；path 为搜索基准目录，缺省为工作目录。结果超 200 条截断并附提示。",
                Permission.READ);
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("pattern", ParamSpec.Type.STRING, "glob 模式，如 **/*.java"),
                ParamSpec.optional("path", ParamSpec.Type.STRING,
                        "搜索基准目录，缺省为工作目录"));
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        String pattern = input.get("pattern").asText();
        Path base = input.hasNonNull("path")
                ? context.resolve(input.get("path").asText())
                : context.workingDirectory();
        if (!Files.isDirectory(base)) {
            return ToolResult.failure("目录不存在：" + base);
        }
        // Java glob 的 **/ 要求至少一层目录，根目录单层文件匹配不到；
        // 因此对以 **/ 开头的 pattern 同时用去掉该前缀的版本匹配
        List<PathMatcher> matchers = new ArrayList<>();
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        if (pattern.startsWith("**/")) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
        }

        List<String> results = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(base)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path rel = base.relativize(it.next());
                if (rel.toString().isEmpty() || !matchesAny(rel, matchers)) {
                    continue;
                }
                if (results.size() >= MAX_RESULTS) {
                    truncated = true;
                    break;
                }
                results.add(base.resolve(rel).toString());
            }
        } catch (IOException e) {
            return ToolResult.failure("遍历目录失败：" + base + "：" + e.getMessage());
        }

        String body = String.join("\n", results);
        if (truncated) {
            body += "\n…（结果过多，已截断，仅显示前 " + MAX_RESULTS + " 条）";
        }
        return ToolResult.success(body).withDisplay(
                "返回 " + results.size() + " 个匹配" + (truncated ? "（已截断）" : ""));
    }

    private static boolean matchesAny(Path rel, List<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(rel)) {
                return true;
            }
        }
        return false;
    }
}
