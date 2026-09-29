package com.acode.team;

import com.acode.provider.ChatMessage;
import com.acode.session.SessionCodec;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Session-local transcript snapshots, including compacted history. */
public final class TeamTranscriptStore {
    private final Path file;
    private final Consumer<String> warning;
    public TeamTranscriptStore(Path directory, String name) {
        this(directory, name, message -> LoggerFactory.getLogger(TeamTranscriptStore.class).warn(message));
    }
    TeamTranscriptStore(Path directory, String name, Consumer<String> warning) {
        TeamManager.validateName(name);
        file = directory.toAbsolutePath().normalize().resolve("transcripts").resolve(name + ".jsonl");
        this.warning = warning;
    }
    public synchronized void append(ChatMessage message) {
        try {
            TeamFileLock.rejectLink(file);
            String line = SessionCodec.encode(message, System.currentTimeMillis());
            if (line == null) throw new IOException("无法编码消息");
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) { warning.accept("队员 transcript 追加失败：" + e.getMessage()); }
    }
    public synchronized void save(List<ChatMessage> history) {
        try {
            TeamFileLock.rejectLink(file.getParent());
            Files.createDirectories(file.getParent());
            TeamFileLock.rejectLink(file);
            var lines = new ArrayList<String>();
            for (ChatMessage message : history) {
                String line = SessionCodec.encode(message, System.currentTimeMillis());
                if (line == null) throw new IOException("无法编码消息");
                lines.add(line);
            }
            Path temp = Files.createTempFile(file.getParent(), ".transcript-", ".tmp");
            try {
                Files.write(temp, lines);
                TeamFileLock.rejectLink(file);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException | RuntimeException e) { warning.accept("队员 transcript 写入失败：" + e.getMessage()); }
    }
    public synchronized List<ChatMessage> read() {
        try {
            TeamFileLock.rejectLink(file);
            if (!Files.exists(file)) return List.of();
            var messages = new ArrayList<ChatMessage>();
            for (String line : Files.readAllLines(file)) SessionCodec.decode(line).ifPresent(entry -> messages.add(entry.message()));
            return List.copyOf(messages);
        } catch (IOException | RuntimeException e) {
            warning.accept("队员 transcript 读取失败：" + e.getMessage());
            return List.of();
        }
    }
}
