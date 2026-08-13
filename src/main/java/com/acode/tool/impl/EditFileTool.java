package com.acode.tool.impl;

import com.acode.tool.BaseTool;
import com.acode.tool.ParamSpec;
import com.acode.tool.Permission;
import com.acode.tool.ToolContext;
import com.acode.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 一次调用内做多段精确替换：每段 old 必须在当前内容中恰好匹配一处。
 * 任一段不匹配或匹配不唯一 → 整体失败、文件字节不变；全部通过后按段顺序一次写回。
 */
public class EditFileTool extends BaseTool {

    public EditFileTool() {
        super("EditFile", "多段精确替换文件内容，任一段不匹配或匹配不唯一则整体失败且文件不动",
                Permission.WRITE);
    }

    @Override
    protected List<ParamSpec> paramSpecs() {
        return List.of(
                ParamSpec.required("file_path", ParamSpec.Type.STRING, "要编辑的文件路径"),
                ParamSpec.required("edits", ParamSpec.Type.ARRAY,
                        "替换段数组，每项含 old（被替换的原文）与 new（替换后的新文）"));
    }

    @Override
    protected ToolResult doExecute(JsonNode input, ToolContext context) {
        Path file = context.resolve(input.get("file_path").asText());
        if (!Files.isRegularFile(file)) {
            return ToolResult.failure("文件不存在：" + file);
        }
        JsonNode editsNode = input.get("edits");
        if (!editsNode.isArray() || editsNode.isEmpty()) {
            return ToolResult.failure("edits 必须是非空数组");
        }
        ArrayNode edits = (ArrayNode) editsNode;

        String current;
        try {
            current = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure("读取文件失败：" + file + "：" + e.getMessage());
        }

        StringBuilder builder = new StringBuilder(current);
        for (JsonNode edit : edits) {
            if (!edit.isObject() || !edit.hasNonNull("old") || !edit.hasNonNull("new")
                    || !edit.get("old").isTextual() || !edit.get("new").isTextual()) {
                return ToolResult.failure("edits 每项必须含字符串字段 old 与 new");
            }
            String old = edit.get("old").asText();
            String replacement = edit.get("new").asText();
            if (old.isEmpty()) {
                return ToolResult.failure("old 不能为空");
            }
            String content = builder.toString();
            int idx = content.indexOf(old);
            if (idx < 0) {
                return ToolResult.failure("未找到匹配内容：" + summarize(old));
            }
            if (content.indexOf(old, idx + 1) >= 0) {
                return ToolResult.failure("匹配不唯一：" + summarize(old) + " 出现多次");
            }
            builder.replace(idx, idx + old.length(), replacement);
        }

        try {
            Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
            return ToolResult.success("已编辑 " + file + "（" + edits.size() + " 处替换）");
        } catch (IOException e) {
            return ToolResult.failure("写入文件失败：" + file + "：" + e.getMessage());
        }
    }

    private static String summarize(String s) {
        String t = s.strip();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}
