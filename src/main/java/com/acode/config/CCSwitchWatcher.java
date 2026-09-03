package com.acode.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class CCSwitchWatcher implements Runnable {

    private static final String SETTINGS_FILE = "settings.json";
    private static final long DEBOUNCE_MS = 200;

    private final Path watchDir;
    private final Runnable onChange;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public CCSwitchWatcher(Path claudeDir, Runnable onChange) {
        this.watchDir = claudeDir;
        this.onChange = onChange;
    }

    public static CCSwitchWatcher startDefault(Runnable onChange) {
        Path claudeDir = Path.of(System.getProperty("user.home"), ".claude");
        CCSwitchWatcher watcher = new CCSwitchWatcher(claudeDir, onChange);
        watcher.start();
        return watcher;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!Files.isDirectory(watchDir)) {
            running.set(false);
            return;
        }
        thread = new Thread(this, "ccswitch-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            watchDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.poll();
                    if (key == null) {
                        key = watchService.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (key == null) {
                    continue;
                }

                boolean settingsChanged = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        settingsChanged = true;
                        break;
                    }
                    Path changed = (Path) event.context();
                    if (changed != null && SETTINGS_FILE.equals(changed.toString())) {
                        settingsChanged = true;
                    }
                }
                boolean reset = key.reset();
                if (!reset) {
                    return;
                }

                if (settingsChanged) {
                    debounce();
                    try {
                        onChange.run();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void debounce() {
        try {
            Thread.sleep(DEBOUNCE_MS);
            WatchService drain = FileSystems.getDefault().newWatchService();
            watchDir.register(drain, StandardWatchEventKinds.ENTRY_MODIFY);
            while (drain.poll() != null) {
                Thread.sleep(50);
            }
            drain.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }
}
