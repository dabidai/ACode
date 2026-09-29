package com.acode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** A CREATE_NEW ownership file, guarded against compare/delete races across JVMs. */
public final class TeamFileLock implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long STALE_MILLIS = 10_000;
    private static final Semaphore[] LOCAL_GATES = new Semaphore[256];
    static {
        for (int i = 0; i < LOCAL_GATES.length; i++) LOCAL_GATES[i] = new Semaphore(1, true);
    }
    private final Path path;
    private final String token;
    private final FileChannel channel;
    private final FileLock guard;
    private boolean closed;
    private Semaphore localGate;

    private TeamFileLock(Path path, String token, FileChannel channel, FileLock guard) {
        this.path = path;
        this.token = token;
        this.channel = channel;
        this.guard = guard;
    }

    public static TeamFileLock acquire(Path path) throws IOException {
        Semaphore gate = gate(path);
        try {
            if (!gate.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("文件锁忙：" + path.getFileName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("文件锁等待已取消");
        }
        boolean transferred = false;
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("文件锁等待已取消");
                var acquired = tryAcquireGuarded(path);
                if (acquired.isPresent()) {
                    acquired.get().localGate = gate;
                    transferred = true;
                    return acquired.get();
                }
                if (attempt < 9) {
                    try { Thread.sleep(ThreadLocalRandom.current().nextInt(5, 101)); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("文件锁等待已取消");
                    }
                }
            }
            throw new IOException("文件锁忙：" + path.getFileName());
        } finally { if (!transferred) gate.release(); }
    }

    public static Optional<TeamFileLock> tryAcquire(Path requested) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("文件锁等待已取消");
        Semaphore gate = gate(requested);
        // Timed zero acquisition respects queued writers, unlike Semaphore.tryAcquire().
        try { if (!gate.tryAcquire(0, TimeUnit.SECONDS)) return Optional.empty(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("文件锁等待已取消");
        }
        boolean transferred = false;
        try {
            var acquired = tryAcquireGuarded(requested);
            if (acquired.isPresent()) {
                acquired.get().localGate = gate;
                transferred = true;
            }
            return acquired;
        } finally { if (!transferred) gate.release(); }
    }

    private static Semaphore gate(Path path) {
        return LOCAL_GATES[Math.floorMod(path.toAbsolutePath().normalize().hashCode(), LOCAL_GATES.length)];
    }

    private static Optional<TeamFileLock> tryAcquireGuarded(Path requested) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("文件锁等待已取消");
        Path path = requested.toAbsolutePath().normalize();
        Path guardPath = path.resolveSibling(path.getFileName() + ".guard");
        rejectLink(guardPath);
        FileChannel channel = FileChannel.open(guardPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock guard = null;
        boolean transferred = false;
        try {
            try { guard = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { return Optional.empty(); }
            if (guard == null) return Optional.empty();
            // The previous owner may delete its ownership file while releasing.
            // Inspect that file only after acquiring the stable OS guard.
            rejectLink(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!confirmedDeadOwner(path)) return Optional.empty();
                Files.delete(path);
            }
            String token = UUID.randomUUID().toString();
            var owner = JSON.createObjectNode().put("token", token).put("pid", ProcessHandle.current().pid())
                    .put("startedAt", ProcessHandle.current().info().startInstant().map(i -> i.toEpochMilli()).orElse(-1L))
                    .put("createdAt", System.currentTimeMillis());
            Files.writeString(path, owner.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            transferred = true;
            return Optional.of(new TeamFileLock(path, token, channel, guard));
        } finally {
            if (!transferred) {
                try { if (guard != null) guard.release(); }
                finally { channel.close(); }
            }
        }
    }

    private static boolean confirmedDeadOwner(Path path) {
        try {
            var owner = JSON.readTree(Files.readString(path));
            if (owner == null || !owner.path("pid").canConvertToLong()
                    || !owner.path("createdAt").canConvertToLong() || !owner.path("startedAt").canConvertToLong()
                    || !owner.path("token").isTextual()) return false;
            long pid = owner.path("pid").longValue(), createdAt = owner.path("createdAt").longValue();
            if (pid <= 0 || createdAt <= 0 || System.currentTimeMillis() - createdAt <= STALE_MILLIS) return false;
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) return true;
            long startedAt = owner.path("startedAt").longValue();
            // A reused PID is not the process that originally owned the lock.
            return startedAt >= 0 && process.get().info().startInstant()
                    .map(start -> start.toEpochMilli() != startedAt).orElse(false);
        } catch (IOException | RuntimeException unknown) { return false; }
    }

    static void rejectLink(Path path) throws IOException {
        Path parent = path.getParent();
        if (!parent.toRealPath().equals(parent.toAbsolutePath().normalize()))
            throw new IOException("存储目录包含链接：" + parent);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path) || !path.toRealPath().equals(path)))
            throw new IOException("存储路径包含链接：" + path);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            rejectLink(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                var owner = JSON.readTree(Files.readString(path));
                if (owner != null && token.equals(owner.path("token").asText())) Files.delete(path);
            }
        } finally {
            try { guard.release(); }
            finally {
                try { channel.close(); }
                finally { localGate.release(); }
            }
        }
        // Never unlink .guard: replacing its inode would split the OS lock domain.
    }
}
