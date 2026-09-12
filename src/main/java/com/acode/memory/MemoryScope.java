package com.acode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 一个记忆根（用户级 {@code ~/.acode/memory/} 或项目级 {@code <项目根>/.acode/memory/}）。
 * 文件名走白名单：必须形如 {@code <type>-<slug>.md}，且解析后的路径必须落在本根内——
 * 模型返回的任何路径穿越写法都在这里被拒绝。
 */
public final class MemoryScope {

    public enum Kind { USER, PROJECT }

    /** 合法记忆文件名：四类前缀 + 短横线 slug + .md（白名单，天然排除路径分隔符与 ..） */
    static final Pattern FILE_NAME =
            Pattern.compile("^(user|feedback|project|reference)-[a-z0-9][a-z0-9-]{0,39}\\.md$");

    static final String INDEX_FILE = "MEMORY.md";

    private static final Logger log = LoggerFactory.getLogger(MemoryScope.class);

    private final Kind kind;
    private final Supplier<Path> base;

    private MemoryScope(Kind kind, Supplier<Path> base) {
        this.kind = kind;
        this.base = base;
    }

    public static MemoryScope user(Path userHome) {
        return new MemoryScope(Kind.USER, () -> userHome);
    }

    public static MemoryScope project(Path projectRoot) {
        return new MemoryScope(Kind.PROJECT, () -> projectRoot);
    }

    /** 根动态求值：主流程用——测试可能在装配之后才注入 @TempDir 项目根 */
    public static MemoryScope user(Supplier<Path> userHome) {
        return new MemoryScope(Kind.USER, userHome);
    }

    public static MemoryScope project(Supplier<Path> projectRoot) {
        return new MemoryScope(Kind.PROJECT, projectRoot);
    }

    public Kind kind() {
        return kind;
    }

    public Path root() {
        return base.get().resolve(".acode").resolve("memory").toAbsolutePath().normalize();
    }

    public Path indexPath() {
        return root().resolve(INDEX_FILE);
    }

    /** 中文标签：供告警与 /memory 输出使用 */
    public String label() {
        return kind == Kind.PROJECT ? "项目级" : "用户级";
    }

    /** 记忆文件的解析路径；文件名不合法或解析后越出本根 → null */
    public Path resolve(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            return null;
        }
        Path root = root();
        Path candidate = root.resolve(fileName).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    /**
     * 本根内的记忆文件（文件名须匹配 {@link #FILE_NAME}），按文件名排序。
     * 目录里其余 {@code .md} 是用户自有文件，不列出也不告警；索引文件被白名单天然排除。
     */
    public List<Path> files() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> files = new ArrayList<>();
            for (Path path : stream.sorted().toList()) {
                if (FILE_NAME.matcher(path.getFileName().toString()).matches()
                        && Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
            return files;
        } catch (IOException e) {
            log.warn("列出记忆目录失败：{}：{}", root, e.getMessage());
            return List.of();
        }
    }

    /** 本根可写？目录缺失时会尝试创建（记忆是新增能力，首次写入才建目录） */
    public boolean writable() {
        Path root = root();
        try {
            Files.createDirectories(root);
            return Files.isWritable(root);
        } catch (IOException e) {
            return false;
        }
    }
}
