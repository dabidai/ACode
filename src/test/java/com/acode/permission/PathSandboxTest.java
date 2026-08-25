package com.acode.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSandboxTest {

    @TempDir
    Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        // 创建父目录，让「文件不存在、父目录存在」的兜底路径可用
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createDirectories(projectRoot.resolve("existing/deep"));
    }

    private PathSandbox sandbox() {
        return new PathSandbox(projectRoot);
    }

    @Test
    void allowsRelativePathInsideProject() {
        assertTrue(sandbox().check("src/main/java/A.java"));
    }

    @Test
    void allowsAbsolutePathInsideProject() {
        assertTrue(sandbox().check(projectRoot.resolve("existing/a.txt").toString()));
    }

    @Test
    void allowsProjectRootItself() {
        assertTrue(sandbox().check(projectRoot.toString()));
    }

    @Test
    void deniesAbsolutePathOutsideProject() {
        String outside = Path.of(System.getProperty("user.home")).resolve("secret.txt").toString();
        assertFalse(sandbox().check(outside));
    }

    @Test
    void deniesParentTraversalEscape() {
        // 足够多的 ../ 越过所有允许根、到达文件系统根目录下（非沙箱内）
        assertFalse(sandbox().check("../../../../../../../../../../../../escape.txt"));
    }

    @Test
    void allowsNotYetExistingPathForWrite() {
        // 文件不存在但父目录存在 → 父目录兜底放行
        assertTrue(sandbox().check(projectRoot.resolve("existing/deep/new.txt").toString()));
    }

    @Test
    void allowsJavaIoTmpdirPath() {
        String tmp = System.getProperty("java.io.tmpdir");
        assertTrue(sandbox().check(Path.of(tmp).resolve("acode-tmpfile.txt").toString()));
    }

    @Test
    void allowsProjectRootItselfBeingSymlink() throws IOException {
        Path realBase = projectRoot.resolve("realbase");
        Files.createDirectories(realBase);
        Path link = projectRoot.resolve("linkbase");
        if (!createSymbolicLink(link, realBase)) {
            return; // 无权限创建符号链接时跳过
        }
        PathSandbox sb = new PathSandbox(link);
        assertTrue(sb.check("inside.txt"));
    }

    @Test
    void deniesSymlinkEscape() throws IOException {
        // 目标在用户主目录（既非项目根也非 tmpdir），文件必须真实存在以解析符号链接
        Path outside = Path.of(System.getProperty("user.home")).resolve("acode-escape-target.txt");
        Files.writeString(outside, "secret");
        try {
            Path link = projectRoot.resolve("link");
            if (!createSymbolicLink(link, outside)) {
                return;
            }
            assertFalse(sandbox().check(link.toString()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void allowsWindowsBackslashRelativePath() {
        assertTrue(sandbox().check("src\\main\\java\\A.java"));
    }

    @Test
    void allowsCaseInsensitiveVariantOnWindows() {
        // Windows 文件系统大小写不敏感：解析后 real path 归一化 → 仍在项目内
        assertTrue(sandbox().check("SRC\\MAIN\\JAVA\\A.java"));
    }

    private boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
