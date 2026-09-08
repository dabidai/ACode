package com.acode.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 大结果落盘存储：把超长工具结果全文写入当前工作目录下
 * {@code <工作目录>/.acode/tool-results/<toolUseId>.txt}。
 * 幂等写：目标文件已存在 → 不覆盖、原样复用（写时用 CREATE_NEW 的 wx 语义，天然防并发覆盖）。
 */
public class SpillStore {

    private final Path root;

    public SpillStore(Path workingDirectory) {
        this.root = workingDirectory.resolve(".acode").resolve("tool-results");
    }

    /** 落盘目录（供测试断言文件位置） */
    public Path root() {
        return root;
    }

    /** 该 id 对应文件的解析路径（尚未保证存在） */
    public Path resolve(String toolUseId) {
        return root.resolve(fileName(toolUseId));
    }

    private static String fileName(String toolUseId) {
        return toolUseId + ".txt";
    }

    /**
     * 幂等写入：文件不存在则写入全文并返回绝对路径；已存在则不覆盖、原样返回既有路径。
     * 并发下 CREATE_NEW 保证只有一个线程真正写入，另一个看到已存在即复用。
     */
    public String store(String toolUseId, String content) throws IOException {
        Files.createDirectories(root);
        Path file = resolve(toolUseId);
        if (!Files.exists(file)) {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
        return file.toAbsolutePath().toString();
    }
}
