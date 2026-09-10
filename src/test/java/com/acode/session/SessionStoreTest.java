package com.acode.session;

import com.acode.provider.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.acode.provider.ChatMessage.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreTest {

    @TempDir
    Path tempDir;

    /** 固定"现在"，让过期判定可控 */
    private static final long NOW = 1_800_000_000L;

    private SessionStore store() {
        return new SessionStore(tempDir, () -> NOW);
    }

    private Path writeSession(String id, String... lines) throws IOException {
        Files.createDirectories(store().dir());
        Path file = store().resolve(id);
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String line(String text, long ts) {
        return SessionCodec.encode(ChatMessage.of(USER, text), ts);
    }

    @Test
    void sessionsLiveUnderProjectLevelAcodesSessionsWithJsonlExtension() {
        SessionStore store = store();
        assertEquals(tempDir.resolve(".acode").resolve("sessions"), store.dir());
        assertTrue(store.resolve("abc").getFileName().toString().endsWith(".jsonl"));
    }

    @Test
    void newIdMatchesTimestampHexFormat() {
        String id = store().newId();
        assertTrue(id.matches("\\d{8}-\\d{6}-[0-9a-f]{4}"), id);
    }

    @Test
    void newIdDoesNotCollideWithinSameSecond() {
        SessionStore store = store();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String id = store.newId();
            assertTrue(ids.add(id), "同一秒内连建会话不得碰撞：" + id);
        }
        assertEquals(3, ids.size());
    }

    @Test
    void listSkipsBadLinesAndKeepsTheRest() throws IOException {
        writeSession("20260101-000000-abcd", line("第一行", NOW - 10),
                "{不是合法 JSON", line("第三行", NOW - 5));
        Session session = store().list().get(0);
        assertEquals(List.of("第一行", "第三行"),
                session.messages().stream().map(ChatMessage::content).toList());
    }

    @Test
    void emptyOrAllBadFileCountsAsEmptySession() throws IOException {
        writeSession("20260101-000001-aaaa");
        writeSession("20260101-000002-bbbb", "{坏", "也坏");
        List<Session> sessions = store().list();
        assertEquals(2, sessions.size(), "空文件与全坏行文件都应被列为空会话");
        assertTrue(sessions.stream().allMatch(s -> s.messages().isEmpty()));
        assertTrue(sessions.stream().noneMatch(Session::expired), "无有效时间戳的文件不算过期");
    }

    @Test
    void missingDirectoryListsNothing() {
        assertTrue(store().list().isEmpty());
    }

    @Test
    void listsByLastActiveDescending() throws IOException {
        writeSession("20260101-000000-aaaa", line("旧", NOW - 1000));
        writeSession("20260101-000001-bbbb", line("新", NOW - 10));
        writeSession("20260101-000002-cccc", line("中", NOW - 100));

        assertEquals(List.of("新", "中", "旧"), store().list().stream()
                .map(s -> s.messages().get(0).content()).toList());
    }

    @Test
    void lastActiveComesFromFinalLine() throws IOException {
        writeSession("20260101-000000-aaaa", line("第一行", NOW - 1000), line("末行", NOW - 3));
        assertEquals(NOW - 3, store().list().get(0).lastActiveEpochSeconds());
    }

    @Test
    void marksSessionsOlderThanThirtyDaysExpiredAndSortsThemLast() throws IOException {
        long stale = NOW - SessionStore.EXPIRY_SECONDS - 1;
        writeSession("20260101-000000-aaaa", line("活跃", NOW - 5000));
        writeSession("20260101-000001-bbbb", line("过期", stale));
        writeSession("20260101-000002-cccc", line("最新", NOW - 1));

        List<Session> sessions = store().list();
        assertEquals(3, sessions.size());
        assertTrue(sessions.get(0).id().endsWith("cccc"), "未过期里最新的排最前");
        assertTrue(sessions.get(1).id().endsWith("aaaa"));
        assertTrue(sessions.get(2).expired(), "超期会话被标记");
        assertTrue(sessions.get(2).id().endsWith("bbbb"), "过期项一律排在未过期项之后");
    }

    @Test
    void expiryThresholdMatchesTheDocumentedThirtyDays() {
        assertEquals(2_592_000L, SessionStore.EXPIRY_SECONDS);
    }

    @Test
    void expiredSessionSortsLastEvenWhenItsTimestampIsNewer() throws IOException {
        // 空文件没有时间戳（不算过期，lastActive 为 0）；过期项的末行 ts 反而更大
        writeSession("20260101-000000-aaaa");
        writeSession("20260101-000001-bbbb", line("过期", NOW - SessionStore.EXPIRY_SECONDS - 1));

        List<Session> sessions = store().list();
        assertEquals(2, sessions.size());
        assertTrue(sessions.get(0).id().endsWith("aaaa"), "未过期项一律排在前");
        assertFalse(sessions.get(0).expired());
        assertTrue(sessions.get(1).expired(), "超期项被标记");
        assertTrue(sessions.get(1).lastActiveEpochSeconds() > sessions.get(0).lastActiveEpochSeconds(),
                "被压到最后的过期项时间戳更大，证明排序按「未过期在前」而非纯时间降序");
    }

    @Test
    void loadUnknownIdReturnsEmpty() {
        assertTrue(store().load("nope").isEmpty());
        assertTrue(store().load(null).isEmpty());
    }

    @Test
    void loadReadsEntriesOfKnownSession() throws IOException {
        writeSession("20260101-000000-abcd", line("甲", NOW - 1), line("乙", NOW));
        Session session = store().load("20260101-000000-abcd").orElseThrow();
        assertEquals(2, session.messages().size());
        assertEquals("20260101-000000-abcd", session.id());
    }

    @Test
    void readEntriesSkipsUnparsableLines() throws IOException {
        Path file = writeSession("20260101-000000-abcd", line("甲", NOW), "half-line-cut-off");
        assertEquals(1, SessionStore.readEntries(file).size());
        assertEquals(0, SessionStore.readEntries(tempDir.resolve("missing.jsonl")).size());
    }
}
