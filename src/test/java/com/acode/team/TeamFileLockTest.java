package com.acode.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TeamFileLockTest {
    @TempDir Path directory;

    @Test void exactlyOneConcurrentOwnerAndLoserCanRetry() throws Exception {
        Path path = directory.resolve("mail.lock");
        var start = new CountDownLatch(1);
        var attempted = new CountDownLatch(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                var acquired = TeamFileLock.tryAcquire(path);
                attempted.countDown();
                try {
                    assertTrue(attempted.await(5, TimeUnit.SECONDS));
                    return acquired.isPresent();
                } finally { if (acquired.isPresent()) acquired.get().close(); }
            }));
            start.countDown();
            assertEquals(1, (futures.get(0).get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (futures.get(1).get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        try (var ignored = TeamFileLock.acquire(path)) { assertTrue(Files.exists(path)); }
        assertFalse(Files.exists(path));
    }

    @Test void oldLiveOwnerAndUnknownMetadataAreNeverStolen() throws Exception {
        Path path = directory.resolve("mail.lock");
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        for (String content : List.of(owner(ProcessHandle.current().pid(), started, 1), "{", "{}")) {
            Files.writeString(path, content);
            assertTrue(TeamFileLock.tryAcquire(path).isEmpty());
            assertEquals(content, Files.readString(path));
        }
    }

    @Test void confirmedExitedOwnerIsReclaimedButRecentOrReplacedLocksAreNot() throws Exception {
        // A real short-lived child gives a PID known to have exited.
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        Path path = directory.resolve("mail.lock");
        String recent = owner(child.pid(), -1, System.currentTimeMillis());
        Files.writeString(path, recent);
        assertTrue(TeamFileLock.tryAcquire(path).isEmpty());
        Files.writeString(path, owner(child.pid(), -1, 1));
        var acquired = TeamFileLock.acquire(path);
        String replacement = owner(ProcessHandle.current().pid(), -1, 1);
        Files.writeString(path, replacement);
        acquired.close(); acquired.close();
        assertEquals(replacement, Files.readString(path), "old owner must not remove a replacement token");
    }

    @Test void waitingIsBoundedAndCancellationPreservesInterrupt() throws Exception {
        Path path = directory.resolve("mail.lock");
        try (var ignored = TeamFileLock.acquire(path)) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertTrue(executor.submit(() -> {
                    assertThrows(IOException.class, () -> TeamFileLock.acquire(path));
                    Thread.currentThread().interrupt();
                    assertThrows(java.io.InterruptedIOException.class, () -> TeamFileLock.acquire(path));
                    return Thread.currentThread().isInterrupted();
                }).get(8, TimeUnit.SECONDS));
            }
        }
    }

    private static String owner(long pid, long startedAt, long createdAt) {
        return "{\"token\":\"old-token\",\"pid\":" + pid + ",\"startedAt\":" + startedAt + ",\"createdAt\":" + createdAt + "}";
    }

    @Test void anotherJvmCannotAcquireUntilOwnerReleases() throws Exception {
        Path path = directory.resolve("process.lock");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LockOwner.class.getName(), path.toString())
                .redirectError(directory.resolve("child-error.log").toFile()).start();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var output = child.inputReader();
                assertEquals("READY", pool.submit(output::readLine).get(10, TimeUnit.SECONDS));
                assertTrue(TeamFileLock.tryAcquire(path).isEmpty());
                child.getOutputStream().write(1);
                child.getOutputStream().flush();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue(), Files.readString(directory.resolve("child-error.log")));
                try (var ignored = TeamFileLock.acquire(path)) { assertTrue(Files.exists(path)); }
            } finally {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static class LockOwner {
        public static void main(String[] args) throws Exception {
            try (var ignored = TeamFileLock.acquire(Path.of(args[0]))) {
                System.out.println("READY");
                System.out.flush();
                System.in.read();
            }
        }
    }
}
