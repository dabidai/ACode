package com.acode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CCSwitchWatcherTest {

    @TempDir
    Path tempDir;

    private Path settingsFile() {
        return tempDir.resolve("settings.json");
    }

    private void createSettingsFile() throws IOException {
        Files.writeString(settingsFile(), """
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:15721"}}
                """);
    }

    @Test
    void modifySettingsTriggersCallback() throws Exception {
        createSettingsFile();
        CountDownLatch latch = new CountDownLatch(1);
        CCSwitchWatcher watcher = new CCSwitchWatcher(tempDir, latch::countDown);
        watcher.start();

        Thread.sleep(300);
        Files.writeString(settingsFile(), """
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:9999"}}
                """);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback should be triggered");
        watcher.stop();
    }

    @Test
    void otherFileChangesDoNotTriggerCallback() throws Exception {
        createSettingsFile();
        Path otherFile = tempDir.resolve("other.json");
        Files.writeString(otherFile, "{}");

        AtomicInteger count = new AtomicInteger(0);
        CCSwitchWatcher watcher = new CCSwitchWatcher(tempDir, count::incrementAndGet);
        watcher.start();

        Thread.sleep(300);
        Files.writeString(otherFile, "{\"changed\": true}");
        Thread.sleep(1000);

        assertEquals(0, count.get(), "callback should not be triggered for non-settings.json changes");
        watcher.stop();
    }

    @Test
    void stopPreventsFurtherCallbacks() throws Exception {
        createSettingsFile();
        AtomicInteger count = new AtomicInteger(0);
        CCSwitchWatcher watcher = new CCSwitchWatcher(tempDir, count::incrementAndGet);
        watcher.start();

        Thread.sleep(300);
        watcher.stop();
        Thread.sleep(200);

        Files.writeString(settingsFile(), """
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:9999"}}
                """);
        Thread.sleep(1000);

        assertEquals(0, count.get(), "callback should not be triggered after stop");
    }

    @Test
    void nonExistentDirectoryDoesNotStart() {
        Path nonExistent = tempDir.resolve("nonexistent");
        AtomicInteger count = new AtomicInteger(0);
        CCSwitchWatcher watcher = new CCSwitchWatcher(nonExistent, count::incrementAndGet);
        watcher.start();

        assertEquals(0, count.get());
        watcher.stop();
    }

    @Test
    void callbackExceptionDoesNotStopWatcher() throws Exception {
        createSettingsFile();
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch secondLatch = new CountDownLatch(1);

        CCSwitchWatcher watcher = new CCSwitchWatcher(tempDir, () -> {
            int n = count.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("test exception");
            }
            secondLatch.countDown();
        });
        watcher.start();

        Thread.sleep(300);
        Files.writeString(settingsFile(), """
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:1111"}}
                """);
        Thread.sleep(1000);

        Files.writeString(settingsFile(), """
                {"env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:2222"}}
                """);

        assertTrue(secondLatch.await(5, TimeUnit.SECONDS), "watcher should continue after callback exception");
        watcher.stop();
    }
}
