package com.acode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolContextTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveHandlesAbsoluteAndRelativePaths() {
        ToolContext ctx = new ToolContext(tempDir);

        // 绝对路径原样使用（不做任何改写）
        Path absolute = tempDir.resolve("abs.txt");
        assertEquals(absolute, ctx.resolve(absolute.toString()), "绝对路径应原样返回");

        // 相对路径基于工作目录解析
        assertEquals(tempDir.resolve("sub").resolve("f.txt"),
                ctx.resolve("sub/f.txt"), "相对路径应基于工作目录解析");

        // 相对路径应 normalize（.. 折叠），避免逃逸路径误入沙箱判断
        assertEquals(tempDir.getParent().resolve("x"),
                ctx.resolve("../x"), "相对路径应 normalize 后再返回");
    }

    @Test
    void planModeFlagRoundTrips() {
        assertEquals(false, new ToolContext(tempDir).planMode(), "缺省非 plan 模式");
        assertEquals(true, new ToolContext(tempDir, true).planMode(), "显式 plan 模式应保留");
    }
}
