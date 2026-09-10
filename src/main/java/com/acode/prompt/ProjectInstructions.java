package com.acode.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目指令文件（ACODE.md）三层加载：项目根 / 项目根 .acode / 用户主目录 .acode。
 * 文件不存在即跳过；三者不做覆盖、按内容拼接，高优先级（项目根）排在最前。
 * 任一来源缺失、畸形或被拒都只降级为告警，不抛错、不阻断启动。
 */
public final class ProjectInstructions {

    /** 三层之间的固定分隔线 */
    static final String SEPARATOR = "\n\n----\n\n";

    private ProjectInstructions() {}

    /** 加载结果：可直接进 system 提示的文本 + 供 UI 输出的告警行 */
    public record LoadResult(String text, List<String> warnings) {}

    public static LoadResult load(Path projectRoot, Path userHome) {
        var expander = new InstructionExpander();
        var parts = new ArrayList<String>();

        // 同一 expander 贯穿三层：已展开集合跨层共享，同一文件不会被重复加载
        add(expander, parts, projectRoot.resolve("ACODE.md"), projectRoot, "项目");
        add(expander, parts, projectRoot.resolve(".acode").resolve("ACODE.md"), projectRoot, "项目");
        add(expander, parts, userHome.resolve(".acode").resolve("ACODE.md"), userHome, "用户主目录");

        return new LoadResult(String.join(SEPARATOR, parts), expander.warnings());
    }

    private static void add(InstructionExpander expander, List<String> parts, Path file,
                            Path boundary, String boundaryLabel) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text = expander.expand(file, boundary, boundaryLabel);
        if (text != null && !text.isBlank()) {
            parts.add(text.strip());
        }
    }
}
