package com.acode.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VirtualThreadsTest {

    @Test
    void poolIsNonNullExecutorService() {
        assertNotNull(VirtualThreads.POOL, "共享线程池应非空");
        assertInstanceOf(ExecutorService.class, VirtualThreads.POOL, "共享线程池应为 ExecutorService");
    }

    @Test
    void poolExecutesSubmittedTask() throws Exception {
        Future<String> future = VirtualThreads.POOL.submit(() -> "done");
        assertEquals("done", future.get(5, TimeUnit.SECONDS), "提交的任务应能执行并返回结果");
    }
}
