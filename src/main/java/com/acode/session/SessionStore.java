package com.acode.session;

import com.acode.provider.ChatMessage;
import com.acode.provider.ToolResultBlock;
import com.acode.provider.ToolUseBlock;
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
import java.util.ArrayList;
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
            Session session = JSON.readValue(file.toFile(), Session.class);
            session.setMessages(repairLegacyRoles(session.getMessages()));
            return session;
        } catch (IOException e) {
            throw new IllegalStateException("读取会话失败：" + file + "：" + e.getMessage(), e);
        }
    }

    /**
     * 修复旧版会话文件（role 未被序列化的时代遗留）加载出的消息角色。
     * 规则（按优先级）：已带 role 的原样保留；含 tool_use 块→ASSISTANT、
     * 含 tool_result 块→USER（工具块是确定性信号）；纯文本按位置交替推断，
     * 首条为 USER（真实会话首条必是用户输入），其余取上一条已解析角色的相反值。
     * 仅重建被修复的消息，全部干净时返回原列表引用（对齐 Conversation.sanitize 的 dirty 模式）。
     * 已知轻微边缘：紧跟 tool_result 的截断续写提示（USER）会被交替规则判成 ASSISTANT，
     * Anthropic 会合并相邻同角色消息，影响仅为该提示渲染无 ● 前缀；新保存的文件不受影响。
     * 只在内存中修复，不改写任何会话文件。
     */
    static List<ChatMessage> repairLegacyRoles(List<ChatMessage> messages) {
        ChatMessage.Role previous = null;
        boolean dirty = false;
        List<ChatMessage> repaired = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message.role() != null) {
                repaired.add(message);
                previous = message.role();
                continue;
            }
            ChatMessage.Role inferred;
            if (containsToolUse(message)) {
                inferred = ChatMessage.Role.ASSISTANT;
            } else if (containsToolResult(message)) {
                inferred = ChatMessage.Role.USER;
            } else if (previous == null) {
                inferred = ChatMessage.Role.USER;
            } else {
                inferred = previous == ChatMessage.Role.USER
                        ? ChatMessage.Role.ASSISTANT : ChatMessage.Role.USER;
            }
            repaired.add(new ChatMessage(inferred, message.blocks()));
            previous = inferred;
            dirty = true;
        }
        return dirty ? repaired : messages;
    }

    private static boolean containsToolUse(ChatMessage message) {
        return message.blocks().stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

    private static boolean containsToolResult(ChatMessage message) {
        return message.blocks().stream().anyMatch(b -> b instanceof ToolResultBlock);
    }

    private synchronized String nextUniqueName() throws IOException {
        long now = Math.max(System.currentTimeMillis(), lastStampMillis + 1);
        lastStampMillis = now;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(STAMP);
    }
}
