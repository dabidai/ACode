package com.acode.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话存储：每个会话保存为独立 JSON 文件（文件名=时间戳），追加不覆盖历史。
 * 目录默认 <code>~/.acode/sessions/</code>。
 */
public class SessionStore {

    private static final String EXT = ".json";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 保证同 JVM 内生成的文件名严格递增，避免同一毫秒碰撞 */
    private static volatile long lastStampMillis = -1;

    private final Path sessionsDir;

    public SessionStore(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    public static Path defaultDir() {
        return Paths.get(System.getProperty("user.home"), ".acode", "sessions");
    }

    /** 保存为新文件；若会话无 id 则按时间戳分配。绝不覆盖已存在文件。 */
    public void save(Session session) {
        try {
            Files.createDirectories(sessionsDir);
            if (session.getId() == null) {
                session.setId(nextUniqueName());
            }
            byte[] bytes = JSON.writeValueAsBytes(session);
            Files.write(sessionsDir.resolve(session.getId() + EXT), bytes,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new IllegalStateException("保存会话失败：" + e.getMessage(), e);
        }
    }

    /** 按创建时间升序返回全部会话（文件名即时间戳，字典序=时间序） */
    public List<Session> list() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(EXT))
                    .sorted()
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列出会话失败：" + e.getMessage(), e);
        }
    }

    public Optional<Session> readLatest() {
        List<Session> sessions = list();
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(sessions.size() - 1));
    }

    public Optional<Session> load(String id) {
        Path file = sessionsDir.resolve(id + EXT);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    private Session read(Path file) {
        try {
            return JSON.readValue(file.toFile(), Session.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取会话失败：" + file + "：" + e.getMessage(), e);
        }
    }

    private synchronized String nextUniqueName() throws IOException {
        long now = Math.max(System.currentTimeMillis(), lastStampMillis + 1);
        lastStampMillis = now;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(STAMP);
    }
}
