package com.acode.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * 一层指令文件的唯一定义（路径与标签的单一来源，load 与 /memory 菜单共用）：
     * boundaryLabel 供越界告警，menuLabel 供菜单展示。
     */
    public record Layer(Path file, Path boundary, String boundaryLabel, String menuLabel) {

        /** 该层当前状态：路径、菜单标签、是否存在与行数（空文件记 0 行；层级缺失不抛错） */
        public LayerState state() {
            if (!Files.isRegularFile(file)) {
                return new LayerState(file, menuLabel, false, 0);
            }
            long lines;
            try {
                lines = Files.readAllLines(file).size();
            } catch (IOException e) {
                lines = 0;
            }
            return new LayerState(file, menuLabel, true, lines);
        }

        /** 创建空指令文件（/memory 选中不存在的层时用）；已存在不覆盖，失败返回 false */
        public boolean createEmpty() {
            if (Files.isRegularFile(file)) {
                return true;
            }
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, "", StandardCharsets.UTF_8);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /** 一层文件的查询结果：/memory 菜单展示用 */
    public record LayerState(Path path, String menuLabel, boolean exists, long lines) {}

    /** 三层指令文件的唯一定义：项目根 / 项目根 .acode / 用户主目录 .acode */
    public static List<Layer> layers(Path projectRoot, Path userHome) {
        return List.of(
                new Layer(projectRoot.resolve("ACODE.md"), projectRoot, "项目", "项目指令"),
                new Layer(projectRoot.resolve(".acode").resolve("ACODE.md"), projectRoot, "项目", "本地指令"),
                new Layer(userHome.resolve(".acode").resolve("ACODE.md"), userHome, "用户主目录", "用户指令"));
    }

    public static LoadResult load(Path projectRoot, Path userHome) {
        var expander = new InstructionExpander();
        var parts = new ArrayList<String>();

        // 同一 expander 贯穿三层：已展开集合跨层共享，同一文件不会被重复加载
        for (Layer layer : layers(projectRoot, userHome)) {
            add(expander, parts, layer.file(), layer.boundary(), layer.boundaryLabel());
        }

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
