package com.acode.tool.impl;

import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读文本文件：可限定起始行与行数，超长文件按上限截断并附提示。
 * 相对路径基于 ToolContext 工作目录解析，绝对路径直接用。
 */
public class ReadFileTool extends BaseTool {

    public static final int MAX_LINES = 2000;

    public ReadFileTool() {
        super("ReadFile",
                "读取文本文件内容，返回文件文本。可传 offset/limit 定位起始行与最大行数，超长自动截断并附提示。"
                        + "需要查看文件内容时用本工具；大文件建议先用 Grep 定位相关行，再按 offset/limit 局部读取。"
                        + "路径相对工作目录解析，也可传绝对路径。二进制文件不可读取，请改用 Bash 执行合适的命令。",
                Permission.READ);
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("file_path", ParamSpec.Type.STRING, "要读取的文件路径"),
                ParamSpec.optional("offset", ParamSpec.Type.INTEGER, "起始行（从 0 开始，缺省 0）"),
                ParamSpec.optional("limit", ParamSpec.Type.INTEGER,
                        "最多读取的行数，缺省为全部（上限 2000 行）"));
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        Path file = context.resolve(input.get("file_path").asText());
        if (!Files.isRegularFile(file)) {
            return ToolResult.failure("文件不存在：" + file);
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, input.has("offset") ? input.get("offset").asInt() : 0);
            int limit = input.has("limit") ? input.get("limit").asInt() : MAX_LINES;
            int end = (int) Math.min(lines.size(), (long) start + limit);
            int showEnd = (int) Math.min(end, (long) start + MAX_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < showEnd; i++) {
                sb.append(lines.get(i)).append('\n');
            }
            boolean truncated = lines.size() > MAX_LINES;
            if (truncated) {
                sb.append("\n…（已截断：共 ").append(lines.size())
                        .append(" 行，返回 ").append(showEnd - start).append(" 行）");
            }
            int returned = Math.max(0, showEnd - start);
            String summary = "返回 " + returned + " 行（L" + (start + 1) + "-" + showEnd + "）"
                    + (truncated ? "（已截断）" : "");
            return ToolResult.success(sb.toString()).withDisplay(summary);
        } catch (IOException e) {
            return ToolResult.failure("读取文件失败：" + file + "：" + e.getMessage());
        }
    }
}
