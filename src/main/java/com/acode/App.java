package com.acode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACode 入口。解析启动参数后把主流程委托给 {@link ConversationController}。
 * <p>不要给 stdout/stderr 强设 UTF-8：中文 Windows 控制台按本机代码页（GBK）解码，
 * 强写 UTF-8 字节会把中文渲染成乱码；JVM 默认跟随控制台代码页，正是正确行为。
 * TUI 内部的中文由 JLine 走控制台宽字符接口写出，与此无关。
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
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
