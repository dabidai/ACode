package com.acode.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ch07 T2：SpillStore 幂等落盘（写 wx 语义，已存在不覆盖）。 */
class SpillStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storeWritesFullContentUnderToolResultsDir() throws Exception {
        SpillStore store = new SpillStore(tempDir);
        String path = store.store("tool-1", "完整结果正文");
        assertTrue(path.contains(".acode") && path.contains("tool-results"));
        Path file = store.resolve("tool-1");
        assertTrue(Files.exists(file));
        assertEquals("完整结果正文", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void storeCreatesDirectoriesAutomatically() throws Exception {
        SpillStore store = new SpillStore(tempDir.resolve("deep").resolve("work"));
        store.store("tool-x", "内容");
        assertTrue(Files.exists(store.root()), "目录应自动创建");
    }

    @Test
    void storeDoesNotOverwriteExistingFile() throws Exception {
        SpillStore store = new SpillStore(tempDir);
        store.store("tool-1", "首次写入");
        Path file = store.resolve("tool-1");
        FileTime before = Files.getLastModifiedTime(file);
        store.store("tool-1", "试图覆盖");
        assertEquals("首次写入", Files.readString(file, StandardCharsets.UTF_8),
                "目标已存在 → 不覆盖、原样复用（wx 语义）");
        assertEquals(before, Files.getLastModifiedTime(file), "二次写入不改文件修改时间");
    }
}
