package com.acode.team;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Durable mail, acknowledged only by the caller after successful request handoff. */
public final class FileMailbox {
    public record Message(String id, String from, String to, String summary, String message,
                          String messageType, long ts, boolean read) {}
    private final Path directory;
    private final Consumer<String> warning;

    public FileMailbox(Path teamDirectory) {
        this(teamDirectory, message -> LoggerFactory.getLogger(FileMailbox.class).warn(message));
    }

    FileMailbox(Path teamDirectory, Consumer<String> warning) {
        directory = teamDirectory.toAbsolutePath().normalize().resolve("mailbox");
        this.warning = warning;
    }

    public Message deliver(String from, String to, String summary, String message, String type) {
        if (from == null || from.isBlank() || message == null) throw new IllegalArgumentException("消息发送者和正文不能为空");
        String messageType = type == null ? "text" : type;
        if (messageType.isBlank()) throw new IllegalArgumentException("消息类型不能为空");
        Message envelope = new Message(UUID.randomUUID().toString(), from, to,
                summary == null ? "" : summary, message, messageType, System.currentTimeMillis(), false);
        try {
            TeamJsonFile file = file(to, true);
            try (var ignored = file.lock()) {
                var messages = new ArrayList<>(decode(file.read(), to));
                messages.add(envelope);
                file.write(TeamJsonFile.JSON.valueToTree(messages));
            }
            return envelope;
        } catch (IOException e) { throw new UncheckedIOException("邮件投递失败：" + e.getMessage(), e); }
    }

    public List<Message> unread(String recipient) {
        return readAll(recipient).stream().filter(message -> !message.read()).toList();
    }

    public List<Message> readAll(String recipient) {
        TeamManager.validateName(recipient);
        try {
            if (!Files.exists(directory)) return List.of();
            // Atomic replacement lets readers observe either complete version, never a partial write.
            return decode(file(recipient, false).read(), recipient);
        } catch (IOException | RuntimeException e) {
            warning.accept("邮箱损坏或不可读，按空邮箱处理：" + recipient + " (" + e.getMessage() + ")");
            return List.of();
        }
    }

    /** IDs from the delivered batch only; concurrently appended mail remains unread. */
    public void acknowledge(String recipient, Collection<String> messageIDs) {
        Set<String> ids = Set.copyOf(messageIDs);
        if (ids.isEmpty()) return;
        try {
            TeamJsonFile file = file(recipient, true);
            try (var ignored = file.lock()) {
                List<Message> messages = decode(file.read(), recipient);
                List<Message> updated = messages.stream().map(message -> ids.contains(message.id())
                        ? new Message(message.id(), message.from(), message.to(), message.summary(), message.message(),
                        message.messageType(), message.ts(), true) : message).toList();
                if (!updated.equals(messages)) file.write(TeamJsonFile.JSON.valueToTree(updated));
            }
        } catch (IOException e) { throw new UncheckedIOException("邮件确认失败：" + e.getMessage(), e); }
    }

    private TeamJsonFile file(String recipient, boolean create) throws IOException {
        TeamManager.validateName(recipient);
        TeamFileLock.rejectLink(directory);
        if (create) Files.createDirectories(directory);
        return new TeamJsonFile(directory.resolve(recipient + ".json"));
    }

    private static List<Message> decode(JsonNode array, String recipient) throws IOException {
        var messages = new ArrayList<Message>();
        var ids = new HashSet<String>();
        for (JsonNode row : array) {
            String id = TeamJsonFile.text(row, "id");
            if (id.isBlank() || !ids.add(id) || !recipient.equals(TeamJsonFile.text(row, "to"))
                    || !row.path("read").isBoolean() || !row.path("ts").isIntegralNumber())
                throw new IOException("邮箱消息结构无效");
            messages.add(new Message(id, TeamJsonFile.text(row, "from"), recipient,
                    TeamJsonFile.text(row, "summary"), TeamJsonFile.text(row, "message"),
                    TeamJsonFile.text(row, "messageType"), row.path("ts").longValue(), row.path("read").booleanValue()));
        }
        return List.copyOf(messages);
    }
}
