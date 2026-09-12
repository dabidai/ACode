package com.acode.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 拉起 FakeMcpServer 子进程的测试共用的工作目录。
 * <p>为什么不用 {@code @TempDir}：Windows 在进程终止后仍会锁住它的当前工作目录数毫秒
 * （实测内核放开目录比 {@code Process.waitFor()} 返回晚约 5~8ms，此时删除该目录必报
 * 「另一个程序正在使用此文件」）。而 JUnit 的 @TempDir 清理紧跟测试方法返回执行，正好
 * 撞进这个窗口，于是 close() 明明返回了、清理却随机失败。多等进程一会儿没用——已实测
 * destroy + 两轮 waitFor 仍是 30/30 失败，锁不在进程存活状态上。
 * <p>改用这个固定目录后，清理时不再需要删除任何被锁的目录，竞态从根上消失。
 * 目录落在 target/ 下，由 {@code mvn clean} 回收；子进程从不往里面写东西。
 */
final class SubprocessWorkingDir {

    private static final Path DIR = Path.of("target", "mcp-subprocess-cwd");

    private SubprocessWorkingDir() {
    }

    static Path get() {
        try {
            Files.createDirectories(DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("创建子进程工作目录失败：" + DIR, e);
        }
        return DIR;
    }
}
