package com.acode.session;

import com.acode.provider.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 活跃会话实例：持有会话文件的追加写入句柄。
 *
 * <p>每追加一条消息<em>先写盘</em>；写盘失败只记告警并继续，绝不因持久化失败打断对话、也不重试。
 * 空会话不落盘——首次追加时才分配 id 并创建文件。
 *
 * <p>上下文压缩会原地重建整段历史（ch07），此时追加语义不再成立，改走 {@link #rewrite}：
 * 写同目录临时文件后原子替换，重写中途崩溃也不会把会话文件写坏。
 */
public class SessionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionRecorder.class);

    private final SessionStore store;
    private final LongSupplier nowSeconds;

    private Path file;
    private BufferedWriter writer;

    /** 挂起重建监听：加载/恢复会话期间为 true，此时读出来的历史不该被当作"重建"整段重写 */
    private boolean suspended;

    public SessionRecorder(SessionStore store) {
        this(store, () -> Instant.now().getEpochSecond());
    }

    /** 包可见：测试注入固定时钟断言落盘时间戳 */
    SessionRecorder(SessionStore store, LongSupplier nowSeconds) {
        this.store = store;
        this.nowSeconds = nowSeconds;
    }

    /** 当前绑定的会话文件；尚无任何落盘时为 null */
    public synchronized Path file() {
        return file;
    }

    public synchronized void suspend() {
        suspended = true;
    }

    public synchronized void resume() {
        suspended = false;
    }

    /** 追加一条消息：先写盘，调用方随后更新内存历史 */
    public synchronized void append(ChatMessage message) {
        String line = SessionCodec.encode(message, nowSeconds.getAsLong());
        if (line == null) {
            log.warn("会话消息编码失败，已跳过落盘");
            return;
        }
        try {
            ensureWriter();
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.warn("会话写盘失败，本轮继续：{}", e.getMessage());
        }
    }

    /** 按重建后的历史整段原子重写；重写失败保留原文件逐行不变 */
    public synchronized void rewrite(List<ChatMessage> messages) {
        if (suspended) {
            return;
        }
        long ts = nowSeconds.getAsLong();
        StringBuilder content = new StringBuilder();
        for (ChatMessage message : messages) {
            String line = SessionCodec.encode(message, ts);
            if (line != null) {
                content.append(line).append('\n');
            }
        }
        Path temp = null;
        try {
            Files.createDirectories(store.dir());
            Path target = file != null ? file : store.resolve(store.newId());
            temp = Files.createTempFile(store.dir(), ".rewrite-", ".tmp");
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            closeWriter(); // 句柄不释放时 Windows 会拒绝替换目标文件
            move(temp, target);
            file = target;
        } catch (IOException e) {
            log.warn("会话整段重写失败，保留原文件：{}", e.getMessage());
            deleteQuietly(temp);
        }
    }

    /** 把句柄切到某个既有会话文件（恢复续写）：后续追加落在该文件，不新建、不分叉 */
    public synchronized void bind(Path sessionFile) {
        closeWriter();
        this.file = sessionFile;
    }

    /** 关闭句柄（/quit、EOF、中断都调）；幂等 */
    public synchronized void close() {
        closeWriter();
    }

    private void ensureWriter() throws IOException {
        if (writer != null) {
            return;
        }
        Files.createDirectories(store.dir());
        if (file == null) {
            file = store.resolve(store.newId());
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("关闭会话句柄失败：{}", e.getMessage());
        }
        writer = null;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("清理临时文件失败：{}", e.getMessage());
        }
    }
}
