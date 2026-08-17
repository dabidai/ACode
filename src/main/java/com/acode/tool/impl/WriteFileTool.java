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
import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖写整个文件：缺失的父目录自动创建。相对路径基于工作目录解析。
 */
public class WriteFileTool extends BaseTool {

    /** diff 展示上限：变更中段行数总和超过此值则省略对比 */
    static final int MAX_DIFF_LINES = 300;
    /** 旧内容大小守卫：超过则跳过读取，不阻断写入 */
    static final long MAX_OLD_FILE_BYTES = 2L * 1024 * 1024;

    public WriteFileTool() {
        super("WriteFile",
                "覆盖写整个文件，缺失的父目录自动创建。新建文件或整体替换内容时用本工具；"
                        + "只改已有文件的小片段时用 EditFile 更安全。路径相对工作目录解析，也可传绝对路径。"
                        + "注意：会完全覆盖原内容，不可恢复。",
                Permission.WRITE);
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
            // 写盘前读旧内容做 diff：新文件、过大、读取失败统一不阻断写入
            boolean oldTooLarge = false;
            boolean oldReadFailed = false;
            String oldText = null;
            if (Files.isRegularFile(file)) {
                try {
                    if (Files.size(file) > MAX_OLD_FILE_BYTES) {
                        oldTooLarge = true;
                    } else {
                        oldText = Files.readString(file, StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    oldReadFailed = true;
                }
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            String confirmation = "已写入 " + file + "（" + content.length() + " 字符）";
            return ToolResult.success(confirmation).withDisplay(
                    buildDisplay(confirmation, oldText, oldTooLarge, oldReadFailed, content));
        } catch (IOException e) {
            return ToolResult.failure("写入文件失败：" + file + "：" + e.getMessage());
        }
    }

    private static String buildDisplay(String confirmation, String oldText, boolean oldTooLarge,
                                       boolean oldReadFailed, String content) {
        StringBuilder display = new StringBuilder(confirmation);
        if (oldTooLarge) {
            display.append("\n…（旧内容过大，省略对比）");
        } else if (oldReadFailed) {
            display.append("\n…（旧内容读取失败，省略对比）");
        } else {
            List<String> oldLines = oldText == null ? List.of() : splitLines(oldText);
            List<String> newLines = splitLines(content);
            List<String> diff = LineDiff.diffLines(oldLines, newLines, MAX_DIFF_LINES);
            if (diff == null) {
                display.append("\n…（变化过大，省略对比）");
            } else {
                for (String line : diff) {
                    display.append('\n').append(line);
                }
            }
        }
        return display.toString();
    }

    /** 按行拆分：去掉末尾换行产生的空段，保留中间空行。 */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i).replace("\r", ""));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
