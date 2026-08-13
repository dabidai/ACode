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
 * 覆盖写整个文件：缺失的父目录自动创建。相对路径基于工作目录解析。
 */
public class WriteFileTool extends BaseTool {

    public WriteFileTool() {
        super("WriteFile", "覆盖写整个文件，缺失的父目录自动创建", Permission.WRITE);
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("file_path", ParamSpec.Type.STRING, "要写入的文件路径"),
                ParamSpec.required("content", ParamSpec.Type.STRING, "要写入的完整内容"));
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        Path file = context.resolve(input.get("file_path").asText());
        String content = input.get("content").asText();
        if (Files.isDirectory(file)) {
            return ToolResult.failure("路径是目录，无法写入：" + file);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return ToolResult.success("已写入 " + file + "（" + content.length() + " 字符）");
        } catch (IOException e) {
            return ToolResult.failure("写入文件失败：" + file + "：" + e.getMessage());
        }
    }
}
