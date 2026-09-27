package com.acode.hook;

import java.nio.file.*;

/** Local subprocess fixture used to verify timeout cleans up both parent and child. */
public class HookSleepProcess {
    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);
        Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
        if (args.length == 1) {
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), HookSleepProcess.class.getName(), marker + ".child", "child").start();
        }
        Thread.sleep(30000);
    }
}
