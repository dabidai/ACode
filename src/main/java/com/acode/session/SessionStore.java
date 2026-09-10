package com.acode.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 项目级会话存储：会话文件为 {@code <项目根>/.acode/sessions/<id>.jsonl}，换项目即换会话池。
 * 只负责目录、id 分配与读取（容忍坏行）；写入与句柄由 {@link SessionRecorder} 持有。
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    static final String EXT = ".jsonl";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int SUFFIX_SPACE = 0x10000;

    /** 过期阈值：末行时间戳距今超过 30 天 → 列表标记「已过期」，只标记不删 */
    static final long EXPIRY_SECONDS = 2_592_000L;

    private static final LongSupplier DEFAULT_CLOCK = () -> Instant.now().getEpochSecond();

    /** 本 JVM 已分配过的会话 id（同一秒内连建多个会话时防碰撞） */
    private static final Set<String> ALLOCATED = ConcurrentHashMap.newKeySet();

    private final Supplier<Path> projectRoot;
    private final LongSupplier nowSeconds;

    public SessionStore(Path projectRoot) {
        this(projectRoot, DEFAULT_CLOCK);
    }

    /** 项目根动态求值：主流程用——测试可能在装配之后才注入 @TempDir 项目根 */
    public static SessionStore forProject(Supplier<Path> projectRoot) {
        return new SessionStore(projectRoot, DEFAULT_CLOCK);
    }

    /** 包可见：测试注入固定时钟，驱动过期判定 */
    SessionStore(Path projectRoot, LongSupplier nowSeconds) {
        this(() -> projectRoot, nowSeconds);
    }

    private SessionStore(Supplier<Path> projectRoot, LongSupplier nowSeconds) {
        this.projectRoot = projectRoot;
        this.nowSeconds = nowSeconds;
    }

    /** 会话目录（供测试断言文件位置） */
    public Path dir() {
        return projectRoot.get().resolve(".acode").resolve("sessions");
    }

    /** 某个 id 对应的会话文件路径（尚未保证存在） */
    public Path resolve(String id) {
        return dir().resolve(id + EXT);
    }

    /**
     * 分配会话 id：{@code yyyyMMdd-HHmmss-} + 4 位小写十六进制随机串。
     * 随机后缀避免同一秒内连建多个会话碰撞；极小概率撞上已有文件时重试。
     */
    public String newId() {
        String stamp = LocalDateTime.now().format(STAMP);
        for (int i = 0; i < 50; i++) {
            String id = stamp + "-" + suffix(ThreadLocalRandom.current().nextInt(SUFFIX_SPACE));
            if (reserve(id)) {
                return id;
            }
        }
        for (int n = 0; n < SUFFIX_SPACE; n++) {
            String id = stamp + "-" + suffix(n);
            if (reserve(id)) {
                return id;
            }
        }
        throw new IllegalStateException("无法分配会话 id：" + stamp);
    }

    /** 同 JVM 内已分配过的 id 不再复用，保证同一秒内连建多个会话绝不碰撞 */
    private boolean reserve(String id) {
        return ALLOCATED.add(id) && !Files.exists(resolve(id));
    }

    private static String suffix(int value) {
        return String.format("%04x", value);
    }

    /** 全部会话：按最后活跃降序（活跃在前），已过期项一律排在未过期项之后 */
    public List<Session> list() {
        Path sessionsDir = dir();
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        long now = nowSeconds.getAsLong();
        List<Session> sessions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            for (Path file : stream.filter(SessionStore::isSessionFile).toList()) {
                sessions.add(readSession(file, now));
            }
        } catch (IOException e) {
            log.warn("列出会话失败：{}", e.getMessage());
            return List.of();
        }
        sessions.sort(Comparator.comparing(Session::expired)
                .thenComparing(Comparator.comparingLong(Session::lastActiveEpochSeconds).reversed()));
        return sessions;
    }

    public Optional<Session> load(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Path file = resolve(id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readSession(file, nowSeconds.getAsLong()));
    }

    /** 逐行读取一个会话文件；坏行直接跳过，文件不可读/缺失按空列表返回，不抛错 */
    public static List<SessionEntry> readEntries(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取会话失败：{}：{}", file, e.getMessage());
            return List.of();
        }
        List<SessionEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            SessionCodec.decode(line).ifPresent(entries::add);
        }
        return entries;
    }

    private Session readSession(Path file, long now) {
        List<SessionEntry> entries = readEntries(file);
        long lastTs = entries.isEmpty() ? 0L : entries.get(entries.size() - 1).ts();
        boolean expired = lastTs > 0 && now - lastTs > EXPIRY_SECONDS;
        return new Session(stripExt(file), lastTs,
                entries.stream().map(SessionEntry::message).toList(), expired);
    }

    private static boolean isSessionFile(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(EXT);
    }

    private static String stripExt(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(EXT) ? name.substring(0, name.length() - EXT.length()) : name;
    }
}
