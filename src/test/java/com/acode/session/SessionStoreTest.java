package com.acode.session;

import com.acode.provider.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.acode.provider.ChatMessage.Role.ASSISTANT;
import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SessionStore store() {
        return new SessionStore(tempDir);
    }

    private static Session session(String id, List<ChatMessage> messages) {
        return new Session(id, System.currentTimeMillis(), messages);
    }

    private static ChatMessage user(String content) {
        return ChatMessage.of(USER, content);
    }

    private static ChatMessage assistant(String content) {
        return ChatMessage.of(ASSISTANT, content);
    }

    @Test
    void saveWritesJsonFileWithAllMessages() {
        Session session = session(null, List.of(
                user("第一轮提问"),
                assistant("第一轮回答"),
                user("第二轮提问")));
        store().save(session);

        assertTrue(session.getId() != null && !session.getId().isBlank(),
                "无 id 的会话应被分配时间戳 id");
        long jsonFiles = countJsonFiles();
        assertEquals(1, jsonFiles, "目录应恰好出现 1 个 .json 文件");

        Optional<Session> loaded = store().readLatest();
        assertTrue(loaded.isPresent());
        assertEquals(List.of("第一轮提问", "第一轮回答", "第二轮提问"),
                loaded.get().getMessages().stream().map(ChatMessage::content).toList(),
                "内容应含全部消息，顺序不变");
    }

    @Test
    void secondSaveCreatesNewFileWithoutModifyingFirst() {
        SessionStore store = store();
        store.save(session(null, List.of(user("会话A"))));
        Path firstFile = onlyJsonFile();
        byte[] firstBytes = read(firstFile);

        store.save(session(null, List.of(user("会话B"))));

        assertEquals(2, countJsonFiles(), "再次保存应出现第 2 个文件");
        assertEquals(new String(firstBytes), new String(read(firstFile)),
                "第 1 个文件不应被修改");
    }

    @Test
    void readLatestReturnsMostRecent() {
        SessionStore store = store();
        store.save(session(null, List.of(user("旧会话"))));
        store.save(session(null, List.of(user("新会话"))));
        Session latest = store.readLatest().orElseThrow();
        assertEquals("新会话", latest.getMessages().get(0).content());
    }

    @Test
    void listReturnsSessionsInCreationOrder() {
        SessionStore store = store();
        store.save(session("a", List.of(user("第一条"))));
        store.save(session("b", List.of(user("第二条"))));
        List<Session> sessions = store.list();
        assertEquals(2, sessions.size());
        assertEquals(List.of("第一条", "第二条"),
                sessions.stream()
                        .map(s -> s.getMessages().get(0).content())
                        .toList());
    }

    @Test
    void loadByExistingIdAndUnknownId() {
        SessionStore store = store();
        store.save(session("known", List.of(user("你好"))));
        assertTrue(store.load("known").isPresent());
        assertFalse(store.load("not-exist").isPresent());
    }

    @Test
    void saveRefusesToOverwriteSameId() {
        SessionStore store = store();
        store.save(session("dup", List.of(user("第一次"))));
        assertThrows(IllegalStateException.class,
                () -> store.save(session("dup", List.of(user("第二次")))));
        assertEquals(1, countJsonFiles(), "同名文件不应被覆盖，仍只有 1 个");
    }

    private int countJsonFiles() {
        try (var stream = Files.list(tempDir)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path onlyJsonFile() {
        try (var stream = Files.list(tempDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst().orElseThrow();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
