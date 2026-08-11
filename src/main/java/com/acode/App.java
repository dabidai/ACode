package com.acode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * ACode 入口。解析启动参数后把主流程委托给 {@link ConversationController}。
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // Windows 默认按 GBK 写 stdout/stderr，强制 UTF-8，避免终端中文乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        boolean resume = hasResumeFlag(args);
        log.info("ACode 启动，resume={}", resume);
        ConversationController.run(resume);
    }

    private static boolean hasResumeFlag(String[] args) {
        for (String arg : args) {
            if ("--resume".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
