package com.acode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * ACode 入口。本阶段（T1）负责解析启动参数并打印横幅；
 * T12 起在此装配完整的对话循环。
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // Windows 默认按 GBK 写 stdout，强制 UTF-8，避免终端中文乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        boolean resume = false;
        for (String arg : args) {
            if ("--resume".equals(arg)) {
                resume = true;
            }
        }

        log.info("ACode 启动，resume={}", resume);
        printBanner();

        if (resume) {
            System.out.println("[恢复会话模式] 将恢复最近一次会话（T12 实现）");
        } else {
            System.out.println("[新会话] 输入 /quit 退出（T12 进入对话界面）");
        }
    }

    private static void printBanner() {
        String banner = """
                 ___   ____    ___   ___   ____
                / _ \\ / ___|  / _ \\ / _ \\ |  _ \\
               | | | | |     | | | | | | || | | |
               | |_| | |___  | |_| | |_| || |_| |
                \\___/ \\____|  \\___/ \\___/ |____/
                              ACode v0.1.0
                """;
        System.out.print(banner);
    }
}
